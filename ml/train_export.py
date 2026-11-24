"""Train a maize classifier and export the ExecuTorch model expected by the Android app."""

import argparse
import csv
import json
import os
from collections import Counter
from importlib.resources import files
from pathlib import Path


LABELS = {
    "leaf": ("healthy", "blight", "pest_damage", "unknown"),
    "whorl": ("larva", "unknown"),
}
SPLITS = ("train", "val", "test")


def load_manifest(path, task):
    labels = LABELS[task]
    rows = {split: [] for split in SPLITS}
    groups = {}
    paths = set()
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not {"path", "label", "group", "split"}.issubset(reader.fieldnames or ()):
            raise ValueError("Expected CSV columns path,label,group,split")
        for line, row in enumerate(reader, start=2):
            relative = (row["path"] or "").strip()
            image = (path.parent / relative).resolve()
            label = (row["label"] or "").strip()
            group = (row["group"] or "").strip()
            split = (row["split"] or "").strip()
            if not relative or not image.is_file() or split not in rows or label not in labels or not group:
                raise ValueError(f"Line {line}: invalid file, label, group, or split")
            if image in paths:
                raise ValueError(f"Line {line}: duplicate image path {image}")
            if groups.setdefault(group, split) != split:
                raise ValueError(f"Line {line}: group {group!r} crosses data splits")
            paths.add(image)
            rows[split].append((image, labels.index(label)))
    for split, examples in rows.items():
        missing = [label for index, label in enumerate(labels)
                   if index not in {target for _, target in examples}]
        if missing:
            raise ValueError(f"{split} is missing labels: {', '.join(missing)}")
    return rows


def evaluate(torch, model, loader, labels, device):
    confusion = [[0 for _ in labels] for _ in labels]
    model.eval()
    with torch.inference_mode():
        for images, targets in loader:
            probabilities = model(images.to(device)).softmax(dim=1)
            scores, predicted = probabilities.topk(k=2, dim=1)
            accepted = (scores[:, 0] >= 0.70) & ((scores[:, 0] - scores[:, 1]) >= 0.18)
            final = torch.where(accepted, predicted[:, 0], labels.index("unknown"))
            for actual, observed in zip(targets.tolist(), final.cpu().tolist()):
                confusion[actual][observed] += 1
    per_class = {}
    for index, label in enumerate(labels):
        hits = confusion[index][index]
        actual = sum(confusion[index])
        predicted = sum(row[index] for row in confusion)
        per_class[label] = {
            "support": actual,
            "precision": hits / predicted if predicted else 0.0,
            "recall": hits / actual if actual else 0.0,
            "f1": 2 * hits / (actual + predicted) if actual + predicted else 0.0,
        }
    return {
        "labels": labels,
        "confusion_matrix": confusion,
        "per_class": per_class,
        "macro_f1": sum(row["f1"] for row in per_class.values()) / len(labels),
    }


def train(args, rows):
    import torch
    from PIL import Image, ImageOps
    from torch.utils.data import DataLoader, Dataset
    from torchvision import models, transforms

    torch.manual_seed(39)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    labels = LABELS[args.task]
    normalize = transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])

    class Images(Dataset):
        def __init__(self, examples, training):
            self.examples = examples
            operations = [transforms.RandomHorizontalFlip()] if training else []
            self.transform = transforms.Compose([*operations, transforms.ToTensor(), normalize])

        def __len__(self):
            return len(self.examples)

        def __getitem__(self, index):
            path, target = self.examples[index]
            with Image.open(path) as source:
                image = ImageOps.exif_transpose(source).convert("RGB")
            image = ImageOps.fit(image, (224, 224), method=Image.Resampling.BILINEAR)
            return self.transform(image), target

    loaders = {
        split: DataLoader(Images(examples, split == "train"), batch_size=args.batch_size,
                          shuffle=split == "train")
        for split, examples in rows.items()
    }
    weights = None if args.random_init else models.MobileNet_V2_Weights.DEFAULT
    model = models.mobilenet_v2(weights=weights)
    model.classifier[1] = torch.nn.Linear(model.last_channel, len(labels))
    model.to(device)
    counts = Counter(target for _, target in rows["train"])
    weights = torch.tensor([1 / counts[index] for index in range(len(labels))], device=device)
    loss_for = torch.nn.CrossEntropyLoss(weight=weights)
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.0003)
    best_score = -1.0
    for epoch in range(args.epochs):
        model.train()
        for images, targets in loaders["train"]:
            optimizer.zero_grad(set_to_none=True)
            loss_for(model(images.to(device)), targets.to(device)).backward()
            optimizer.step()
        validation = evaluate(torch, model, loaders["val"], labels, device)
        score = validation["macro_f1"]
        print(f"Epoch {epoch + 1}/{args.epochs}, validation macro F1 {score:.3f}", flush=True)
        if score > best_score:
            best_score = score
            best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}

    model.load_state_dict(best_state)
    report = evaluate(torch, model, loaders["test"], labels, device)
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = f"maize-{args.task}"
    (args.output / f"{prefix}-metrics.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.metrics_only:
        return

    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower
    from executorch.runtime import Runtime

    if os.name == "nt" and "FLATC_EXECUTABLE" not in os.environ:
        # ExecuTorch 1.1's Windows wheel ships flatc in a different location from its lookup.
        bundled_flatc = files("executorch").joinpath("data", "bin", "flatc.exe")
        if bundled_flatc.is_file():
            os.environ["FLATC_EXECUTABLE"] = str(bundled_flatc)

    model.cpu().eval()
    model_path = args.output / f"{prefix}.pte"
    example = (torch.zeros(1, 3, 224, 224),)
    exported = torch.export.export(model, example)
    program = to_edge_transform_and_lower(exported, partitioner=[XnnpackPartitioner()])
    binary = program.to_executorch().buffer
    runtime = Runtime.get()
    restored_program = runtime.load_program(binary)
    restored = restored_program.load_method("forward")
    check_batch, _ = next(iter(loaders["test"]))
    with torch.inference_mode():
        for image in check_batch[:3]:
            batch = image.unsqueeze(0)
            logits = restored.execute([batch])[0]
            torch.testing.assert_close(logits, model(batch), rtol=1e-3, atol=1e-4)
    model_path.write_bytes(binary)
    print(f"Verified and exported {model_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--task", choices=LABELS, required=True)
    parser.add_argument("--output", type=Path, default=Path("ml/build"))
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--metrics-only", action="store_true", help="Train and report metrics without export")
    parser.add_argument("--random-init", action="store_true", help="Skip pretrained weights for offline smoke tests")
    args = parser.parse_args()
    if args.epochs < 1 or args.batch_size < 1:
        parser.error("epochs and batch size must be positive")
    try:
        rows = load_manifest(args.manifest, args.task)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    for split, examples in rows.items():
        counts = Counter(LABELS[args.task][target] for _, target in examples)
        print(f"{split}: {dict(counts)}", flush=True)
    if not args.validate_only:
        train(args, rows)


if __name__ == "__main__":
    main()

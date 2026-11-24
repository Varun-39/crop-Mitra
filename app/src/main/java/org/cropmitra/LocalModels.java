package org.cropmitra;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;
import org.pytorch.executorch.extension.llm.LlmGenerationConfig;
import org.pytorch.executorch.extension.llm.LlmModule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.cropmitra.Advisory.*;

final class LocalModels {
    static final String LEAF = "maize-leaf.pte";
    static final String PEST = "maize-whorl.pte";
    static final String LLAMA = "llama.pte";
    static final String TOKENIZER = "tokenizer.model";
    private static final int SIDE = 224;
    private static final String TAG = "CropMitra";
    private final File directory;
    private LlmModule llama;
    private Module vision;
    private String visionFile;

    record VisionResult(Finding finding, long millis) {}
    enum AdviceSource { LLAMA, MISSING, REJECTED }
    record AdviceResult(Action action, AdviceSource source, long millis) {}

    LocalModels(File directory) {
        this.directory = directory;
    }

    boolean installed(String name) {
        return new File(directory, name).length() > 0;
    }

    void install(ContentResolver resolver, Uri uri, String name) throws IOException {
        Files.createDirectories(directory.toPath());
        File temporary = File.createTempFile("import-", ".tmp", directory);
        try {
            try (InputStream input = resolver.openInputStream(uri);
                 OutputStream output = Files.newOutputStream(temporary.toPath())) {
                if (input == null) throw new IOException("Could not open the model file");
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, read);
                }
            }
            if (temporary.length() == 0) throw new IOException("Model file is empty");
            Files.move(temporary.toPath(), new File(directory, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (LLAMA.equals(name) || TOKENIZER.equals(name)) {
                if (llama != null) llama.resetNative();
                llama = null;
            }
            if (name.equals(visionFile)) {
                vision.destroy();
                vision = null;
                visionFile = null;
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    static Bitmap readPhoto(ContentResolver resolver, Uri uri) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                (decoder, info, source) -> {
                    int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (longest > 1280) {
                        float scale = 1280f / longest;
                        decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                                Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                });
    }

    VisionResult classify(Bitmap photo, boolean followUp) {
        long start = SystemClock.elapsedRealtime();
        String name = followUp ? PEST : LEAF;
        Finding[] labels = followUp
                ? new Finding[]{Finding.LARVA, Finding.UNKNOWN}
                : new Finding[]{Finding.HEALTHY, Finding.BLIGHT, Finding.PEST_DAMAGE, Finding.UNKNOWN};
        if (vision == null || !name.equals(visionFile)) {
            if (vision != null) vision.destroy();
            vision = null;
            visionFile = null;
            vision = Module.load(new File(directory, name).getAbsolutePath());
            visionFile = name;
        }
        float[] logits = vision.forward(EValue.from(toTensor(photo)))[0].toTensor().getDataAsFloatArray();
        if (logits.length != labels.length) {
            throw new IllegalStateException("Unexpected class count in " + name);
        }
        int best = 0;
        for (int i = 0; i < logits.length; i++) {
            if (!Float.isFinite(logits[i])) throw new IllegalStateException("Non-finite vision output");
            if (logits[i] > logits[best]) best = i;
        }
        double total = 0;
        double runnerUp = 0;
        for (int i = 0; i < logits.length; i++) {
            double weight = Math.exp(logits[i] - logits[best]);
            total += weight;
            if (i != best) runnerUp = Math.max(runnerUp, weight);
        }
        // shortcut: provisional rejection thresholds, calibrate on held-out local field photos.
        Finding finding = 1 / total >= .70 && (1 - runnerUp) / total >= .18
                ? labels[best] : Finding.UNKNOWN;
        return new VisionResult(finding, SystemClock.elapsedRealtime() - start);
    }

    private Tensor toTensor(Bitmap photo) {
        int size = Math.min(photo.getWidth(), photo.getHeight());
        Bitmap square = Bitmap.createBitmap(photo, (photo.getWidth() - size) / 2,
                (photo.getHeight() - size) / 2, size, size);
        Bitmap image = Bitmap.createScaledBitmap(square, SIDE, SIDE, true);
        int plane = SIDE * SIDE;
        int[] pixels = new int[plane];
        image.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
        float[] values = new float[plane * 3];
        for (int i = 0; i < plane; i++) {
            int rgb = pixels[i];
            values[i] = (((rgb >> 16) & 255) / 255f - .485f) / .229f;
            values[plane + i] = (((rgb >> 8) & 255) / 255f - .456f) / .224f;
            values[plane * 2 + i] = ((rgb & 255) / 255f - .406f) / .225f;
        }
        return Tensor.fromBlob(values, new long[]{1, 3, SIDE, SIDE});
    }

    AdviceResult advise(Evidence evidence) {
        List<Action> choices = eligible(evidence);
        Action fallback = choices.get(0);
        if (!installed(LLAMA) || !installed(TOKENIZER)) {
            return new AdviceResult(fallback, AdviceSource.MISSING, 0);
        }
        long start = SystemClock.elapsedRealtime();
        try {
            if (llama == null) {
                LlmModule candidate = new LlmModule(new File(directory, LLAMA).getAbsolutePath(),
                        new File(directory, TOKENIZER).getAbsolutePath(), 0f);
                if (candidate.load() != 0) {
                    candidate.resetNative();
                    throw new IllegalStateException("ExecuTorch could not load Llama");
                }
                llama = candidate;
            }
            llama.resetContext();
            StringBuilder output = new StringBuilder();
            LlmGenerationConfig config = LlmGenerationConfig.create()
                    .seqLen(384).maxNewTokens(16).temperature(0f).echo(false).build();
            int status = llama.generate(prompt(evidence, choices), config, output::append);
            Action action = status == 0 ? parse(output.toString(), choices) : null;
            if (action != null) {
                return new AdviceResult(action, AdviceSource.LLAMA, SystemClock.elapsedRealtime() - start);
            }
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Local Llama advisory failed", error);
        }
        return new AdviceResult(fallback, AdviceSource.REJECTED, SystemClock.elapsedRealtime() - start);
    }

    void close() {
        if (llama != null) llama.resetNative();
        if (vision != null) vision.destroy();
    }
}

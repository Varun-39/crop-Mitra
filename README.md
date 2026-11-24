# Crop Mitra

An offline Android prototype for maize scouting in Tamil Nadu. Crop Mitra can assess a leaf photo, ask for a whorl close-up when the first photo suggests fall armyworm damage, and display a locally sourced next step in English, Tamil, or Hindi.

## Run the prototype

1. Open this repository in Android Studio with a Gradle JDK of 17, Android SDK 35, and platform-tools installed. Connect an Arm Android device on API 28 or higher. You can also build and test from a terminal.

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Open **Try a sample case** and choose **Possible fall armyworm damage**. Tap **Get next step**, move to the sample with a visible larva, and ask again. These cases use labeled sample observations. They exercise the flow without model files.
3. For real inference, open **Offline model setup** in the app. Import the two vision exports and the Llama pair shown below. You can start with just the Llama pair and exercise the advisory on a labeled sample. Restart in airplane mode with Wi-Fi off and confirm that the result says **Selected by Llama on this device**.

| Import slot | File |
| --- | --- |
| Leaf model | `maize-leaf.pte` from the training script |
| Whorl model | `maize-whorl.pte` from the training script |
| Llama model | `Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte` |
| Llama tokenizer | `tokenizer.model` from the same model release |

Start with the [ExecuTorch Community Llama 3.2 1B Instruct SpinQuant files](https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/tree/main). The model file is roughly 1.14 GB. Record the revision, file hashes, and on-device result before using the pair in a demo. If the model fails to load, check the app log with `adb logcat -s CropMitra` and use an XNNPACK export built for the pinned ExecuTorch 1.1 runtime.

## Prepare the vision models

1. Download the maize portion of the [ICAR-IASRI field dataset](https://aikosh.indiaai.gov.in/home/datasets/details/crop_disease_and_insect_pest_image_dataset_for_rice_and_maize.html) under the publisher’s data terms. Look for healthy maize, Turcicum leaf blight, fall armyworm damage, and look-alikes. The leaf model uses the labels `healthy`, `blight`, `pest_damage`, and `unknown`.
2. Label visible larvae in whorl close-ups as `larva` and plausible negatives as `unknown`. The [KaraAgro maize dataset](https://dataverse.harvard.edu/dataset.xhtml?persistentId=doi:10.7910/DVN/CXUMDS) is a candidate source. Inspect the annotations and keep local photos for validation. Damage alone does not establish that a larva is visible.
3. Write one CSV per task with `path,label,group,split` columns. Paths are relative to the CSV. Use a stable plant, plot, or capture-session ID as the group. Assign it to exactly one of `train`, `val`, or `test`. Include every class in each split. Deduplicate images across sources before splitting. Keep example files and dataset terms outside the source repository.

   ```csv
   path,label,group,split
   images/plant-a.jpg,blight,plot-01,train
   images/plant-b.jpg,healthy,plot-02,val
   images/plant-c.jpg,pest_damage,plot-03,test
   ```

   The three lines illustrate the format. A complete manifest needs every class in each split.

4. Run the training and export commands from a clean Python environment. The default is ImageNet transfer learning. Each executable is checked against PyTorch on held-out images before the script writes the `.pte`.

   ```powershell
   python -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r ml/requirements.txt
   .\.venv\Scripts\python.exe ml/train_export.py data/leaf.csv --task leaf
   .\.venv\Scripts\python.exe ml/train_export.py data/whorl.csv --task whorl
   ```

   Add `--validate-only` to check labels and split integrity without ML dependencies. The outputs in `ml/build` include per-class metrics and confusion matrices. Model files, datasets, and the debug APK are build artifacts.

## How the decision works

- The vision stage runs a MobileNetV2 ExecuTorch model on a centred 224 × 224 RGB crop. A second model handles whorl close-ups. Ambiguous or unsupported evidence can lead to a retake.
- The farmer selects crop stage and the visible damage grade for the sampled plant. The prototype’s assisted categories are ordinal. A field infestation estimate would require sampling multiple plants.
- Before asking Llama, the app selects the allowed actions for the evidence. Llama returns a short action ID, and the app renders only the matching localized card. Invalid responses and unavailable models use visibly labeled built-in guidance.
- Advisory references include [TNAU maize leaf blight](https://agritech.tnau.ac.in/crop_protection/maize_disease/maize_4.html), [TNAU fall armyworm](https://agritech.tnau.ac.in/crop_protection/maize/crop_prot_maize_fall_armyworm.html), and the [CIMMYT scouting and management guide](https://repository.cimmyt.org/server/api/core/bitstreams/4b6c4442-e04c-440d-a711-0bbc0c163f3b/content). Have local speakers and an extension worker review the prototype cards before field use.

## Field acceptance

Run the same held-out cases with the first photo only and with the whorl follow-up. Record wrong confident results, how often the app gives a result, per-class precision and recall, and how often the next step changes. Tune the provisional rejection thresholds using validation data before opening the final test set. On the target Arm phone, record the model sizes, cold and warm times, and sampled memory using `adb shell dumpsys meminfo org.cropmitra`. Include healthy maize, probable blight, pest damage, likely visible larvae, look-alikes, and unusable photos in the demo.

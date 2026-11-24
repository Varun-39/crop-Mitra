package org.cropmitra;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.cropmitra.Advisory.*;
import static org.cropmitra.LocalModels.*;

public final class MainActivity extends Activity {
    private static final int PICK_PHOTO = 10;
    private static final int TAKE_PHOTO = 11;
    private static final int IMPORT_MODEL = 12;
    private static final int PAPER = 0xfff3f5ea;
    private static final int INK = 0xff193524;
    private static final int MUTED = 0xff526458;
    private static final int GREEN = 0xff236344;
    private static final int SOFT = 0xffe4efe0;
    private static final int FOREST = 0xff133e2e;
    private static final int ACCENT = 0xfff2dc94;
    private static final String[] LANGUAGES = {"English", "தமிழ்", "हिन्दी"};
    private static final String[] LANGUAGE_CODES = {"en", "ta", "hi"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LocalModels models;
    private Context languageContext;
    private int language;
    private LinearLayout content;
    private ScrollView scroll;
    private View adviceCard;
    private Stage stage = Stage.UNKNOWN;
    private Severity severity = Severity.UNKNOWN;
    private Finding finding;
    private Bitmap photo;
    private AdviceResult advice;
    private boolean followUp;
    private boolean pendingFollowUp;
    private boolean sample;
    private boolean busy;
    private boolean modelSetupOpen;
    private Uri cameraUri;
    private String importName;
    private long visionMillis;
    private int message;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        models = new LocalModels(new File(getFilesDir(), "models"));
        language = getPreferences(MODE_PRIVATE).getInt("language", 0);
        if (state != null) {
            cameraUri = state.getParcelable("camera");
            importName = state.getString("import");
            pendingFollowUp = state.getBoolean("pendingFollowUp");
            followUp = state.getBoolean("followUp");
            sample = state.getBoolean("sample");
            modelSetupOpen = state.getBoolean("models");
            stage = Stage.values()[state.getInt("stage")];
            severity = Severity.values()[state.getInt("severity")];
            int savedFinding = state.getInt("finding", -1);
            if (savedFinding >= 0) finding = Finding.values()[savedFinding];
        }
        setLanguage(language);
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("camera", cameraUri);
        state.putString("import", importName);
        state.putBoolean("pendingFollowUp", pendingFollowUp);
        state.putBoolean("followUp", followUp);
        state.putBoolean("sample", sample);
        state.putBoolean("models", modelSetupOpen);
        state.putInt("stage", stage.ordinal());
        state.putInt("severity", severity.ordinal());
        state.putInt("finding", finding == null ? -1 : finding.ordinal());
    }

    @Override protected void onDestroy() {
        worker.execute(models::close);
        worker.shutdown();
        super.onDestroy();
    }

    private void setLanguage(int selected) {
        language = selected;
        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(LANGUAGE_CODES[selected]));
        languageContext = createConfigurationContext(config);
        getPreferences(MODE_PRIVATE).edit().putInt("language", selected).apply();
    }

    private void render() {
        int oldScroll = scroll == null ? 0 : scroll.getScrollY();
        scroll = new ScrollView(this);
        adviceCard = null;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        content = column();
        content.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(content);
        setContentView(scroll);
        scroll.requestApplyInsets();

        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("Crop Mitra", 23, INK, true);
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button languageButton = button(LANGUAGES[language], false,
                () -> choose(R.string.language, LANGUAGES, i -> {
                    setLanguage(i);
                    render();
                }));
        languageButton.setContentDescription(t(R.string.language) + " · " + LANGUAGES[language]);
        masthead.addView(languageButton);
        content.addView(masthead);

        LinearLayout hero = card(FOREST);
        hero.addView(text(t(R.string.pilot), 14, ACCENT, true));
        TextView headline = text(t(R.string.hero), 32, Color.WHITE, true);
        headline.setPadding(0, dp(8), 0, dp(5));
        hero.addView(headline);
        hero.addView(text(t(R.string.tagline), 16, 0xffe3ebe1, false));

        LinearLayout evidence = card(Color.WHITE);
        heading(evidence, R.string.photo);
        if (photo != null) {
            ImageView preview = new ImageView(this);
            preview.setImageBitmap(photo);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setBackground(rounded(SOFT));
            preview.setContentDescription(t(R.string.photo));
            add(evidence, preview, 10, dp(184));
        }
        if (sample) evidence.addView(text(t(R.string.sample_badge), 14, GREEN, true));
        if (finding != null) {
            add(evidence, text(t(findingLabel(finding)), 19, INK, true), 8, -2);
            if (!sample && visionMillis > 0) {
                evidence.addView(text(languageContext.getString(R.string.vision_time, visionMillis), 13, MUTED, false));
            }
        }
        boolean whorl = followUp || finding == Finding.PEST_DAMAGE
                && advice != null && advice.action() == Action.WHORL_PHOTO;
        if (!sample || finding == null) {
            add(evidence, button(t(whorl ? R.string.whorl_camera : R.string.camera), true,
                    () -> takePhoto(whorl)), 12, -2);
            add(evidence, button(t(whorl ? R.string.whorl_gallery : R.string.gallery), false,
                    () -> pickPhoto(whorl)), 8, -2);
        }
        if (finding == null) {
            add(evidence, button(t(R.string.sample), false, this::pickSample), 8, -2);
        }
        if (message != 0) {
            TextView status = text(t(message), 15, INK, false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            add(evidence, status, 10, -2);
        }
        if (finding != null) {
            LinearLayout details = card(Color.WHITE);
            heading(details, R.string.details);
            String[] stages = languageContext.getResources().getStringArray(R.array.stages);
            String[] severities = languageContext.getResources().getStringArray(R.array.severities);
            add(details, text(t(R.string.stage_select), 15, MUTED, true), 10, -2);
            add(details, button(stages[stage.ordinal()], false,
                    () -> choose(R.string.stage_select, stages, i -> {
                        stage = Stage.values()[i];
                        advice = null;
                        render();
                    })), 4, -2);
            if (finding != Finding.HEALTHY && finding != Finding.UNKNOWN) {
                add(details, text(t(R.string.severity_select), 15, MUTED, true), 10, -2);
                add(details, button(severities[severity.ordinal()], false,
                        () -> choose(R.string.severity_select, severities, i -> {
                            severity = Severity.values()[i];
                            advice = null;
                            render();
                        })), 4, -2);
                add(details, text(t(R.string.severity_hint), 14, MUTED, false), 6, -2);
            }
            add(details, button(t(R.string.get_advice), true, this::getAdvice), 12, -2);
        }
        if (advice != null) {
            LinearLayout result = card(SOFT);
            adviceCard = result;
            heading(result, R.string.next);
            add(result, text(t(actionLabel(advice.action())), 19, INK, true), 8, -2);
            add(result, text(t(switch (advice.source()) {
                case LLAMA -> R.string.source_llama;
                case MISSING -> R.string.source_missing;
                case REJECTED -> R.string.source_rejected;
            }), 14, MUTED, false), 10, -2);
            if (advice.millis() > 0) {
                result.addView(text(languageContext.getString(R.string.advice_time, advice.millis()), 13, MUTED, false));
            }
            if (finding == Finding.BLIGHT || finding == Finding.PEST_DAMAGE || finding == Finding.LARVA) {
                result.addView(text(t(finding == Finding.BLIGHT
                        ? R.string.source_tnau : R.string.source_cimmyt), 13, MUTED, false));
            }
            if (advice.action() == Action.WHORL_PHOTO) {
                if (sample) {
                    add(result, button(t(R.string.demo_followup), true, () -> {
                        finding = Finding.LARVA;
                        severity = Severity.SEVERE;
                        followUp = true;
                        advice = null;
                        render();
                    }), 10, -2);
                } else {
                    add(result, button(t(R.string.whorl_camera), true, () -> takePhoto(true)), 10, -2);
                }
            }
        }
        if (finding != null) {
            add(content, button(t(R.string.new_case), false, this::clearCase), 12, -2);
        }
        LinearLayout setup = card(Color.WHITE);
        setup.addView(button(t(R.string.model_setup), false, () -> {
            modelSetupOpen = !modelSetupOpen;
            render();
        }));
        if (modelSetupOpen) {
            add(setup, text(t(R.string.model_setup_note), 15, MUTED, false), 8, -2);
            addModel(setup, LEAF, R.string.vision_model);
            addModel(setup, PEST, R.string.pest_model);
            addModel(setup, LLAMA, R.string.llama_model);
            addModel(setup, TOKENIZER, R.string.tokenizer);
        }
        ScrollView renderedScroll = scroll;
        renderedScroll.post(() -> renderedScroll.scrollTo(0, oldScroll));
    }

    private void getAdvice() {
        Evidence input = new Evidence(finding, stage, severity, followUp);
        begin(R.string.busy);
        worker.execute(() -> {
            AdviceResult result = models.advise(input);
            runOnUiThread(() -> {
                completeWork(() -> {
                    advice = result;
                    message = 0;
                });
                if (adviceCard != null && !isDestroyed() && !isFinishing()) {
                    View card = adviceCard;
                    ScrollView renderedScroll = scroll;
                    renderedScroll.post(() -> renderedScroll.smoothScrollTo(0, card.getTop()));
                }
            });
        });
    }

    private void pickSample() {
        choose(R.string.sample, languageContext.getResources().getStringArray(R.array.sample_cases), i -> {
            sample = true;
            finding = new Finding[]{Finding.HEALTHY, Finding.BLIGHT, Finding.PEST_DAMAGE}[i];
            stage = Stage.EARLY;
            severity = finding == Finding.BLIGHT ? Severity.MODERATE : Severity.UNKNOWN;
            message = 0;
            render();
        });
    }

    private void clearCase() {
        photo = null;
        finding = null;
        advice = null;
        sample = false;
        followUp = false;
        visionMillis = 0;
        stage = Stage.UNKNOWN;
        severity = Severity.UNKNOWN;
        message = 0;
        scroll.scrollTo(0, 0);
        render();
    }

    private void addModel(LinearLayout setup, String name, int label) {
        add(setup, button(languageContext.getString(models.installed(name)
                ? R.string.replace : R.string.install, t(label)), false, () -> {
                    importName = name;
                    openDocument("*/*", IMPORT_MODEL);
                }), 6, -2);
    }

    private void pickPhoto(boolean closeUp) {
        if (!hasVision(closeUp)) return;
        pendingFollowUp = closeUp;
        openDocument("image/*", PICK_PHOTO);
    }

    private void openDocument(String type, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        try {
            startActivityForResult(intent, request);
        } catch (android.content.ActivityNotFoundException error) {
            message = request == IMPORT_MODEL ? R.string.failed_import : R.string.bad_image;
            render();
        }
    }

    private boolean hasVision(boolean closeUp) {
        if (models.installed(closeUp ? PEST : LEAF)) return true;
        message = closeUp ? R.string.need_pest : R.string.need_leaf;
        modelSetupOpen = true;
        render();
        return false;
    }

    private void takePhoto(boolean closeUp) {
        if (!hasVision(closeUp)) return;
        try {
            File photos = new File(getCacheDir(), "photos");
            if (!photos.isDirectory() && !photos.mkdirs()) throw new IOException("Could not create photo cache");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".photos",
                    File.createTempFile("crop-", ".jpg", photos));
            pendingFollowUp = closeUp;
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.setClipData(ClipData.newRawUri("Crop photo", cameraUri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(camera, TAKE_PHOTO);
        } catch (IOException | RuntimeException error) {
            if (cameraUri != null) getContentResolver().delete(cameraUri, null, null);
            cameraUri = null;
            message = R.string.camera_unavailable;
            render();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == TAKE_PHOTO) {
            Uri captured = cameraUri;
            cameraUri = null;
            if (captured == null) return;
            if (result == RESULT_OK) analyse(captured, pendingFollowUp, true);
            else getContentResolver().delete(captured, null, null);
        } else if (result == RESULT_OK && data != null && data.getData() != null) {
            if (request == PICK_PHOTO) analyse(data.getData(), pendingFollowUp, false);
            if (request == IMPORT_MODEL && importName != null) importModel(data.getData(), importName);
        }
    }

    private void analyse(Uri uri, boolean closeUp, boolean deletePhoto) {
        begin(R.string.busy);
        worker.execute(() -> {
            try {
                Bitmap image = readPhoto(getContentResolver(), uri);
                VisionResult result = models.classify(image, closeUp);
                finishWork(() -> {
                    photo = image;
                    finding = result.finding();
                    visionMillis = result.millis();
                    followUp = closeUp;
                    sample = false;
                    if (!closeUp) severity = Severity.UNKNOWN;
                    advice = null;
                    message = 0;
                });
            } catch (IOException | SecurityException error) {
                finishWork(() -> message = R.string.bad_image);
            } catch (RuntimeException | LinkageError error) {
                Log.w("CropMitra", "Local vision failed", error);
                finishWork(() -> message = R.string.vision_error);
            } finally {
                if (deletePhoto) getContentResolver().delete(uri, null, null);
            }
        });
    }

    private void importModel(Uri uri, String name) {
        begin(R.string.importing);
        worker.execute(() -> {
            try {
                models.install(getContentResolver(), uri, name);
                finishWork(() -> message = R.string.installed);
            } catch (IOException | RuntimeException | LinkageError error) {
                Log.w("CropMitra", "Model import failed", error);
                finishWork(() -> message = R.string.failed_import);
            }
        });
    }

    private void begin(int status) {
        message = status;
        busy = true;
        render();
    }

    private void finishWork(Runnable update) {
        runOnUiThread(() -> completeWork(update));
    }

    private void completeWork(Runnable update) {
        if (isDestroyed() || isFinishing()) return;
        busy = false;
        update.run();
        render();
    }

    private int findingLabel(Finding value) {
        return switch (value) {
            case HEALTHY -> R.string.finding_healthy;
            case BLIGHT -> R.string.finding_blight;
            case PEST_DAMAGE -> R.string.finding_pest_damage;
            case LARVA -> R.string.finding_larva;
            case UNKNOWN -> R.string.finding_unknown;
        };
    }

    private int actionLabel(Action value) {
        return switch (value) {
            case RETAKE -> followUp ? R.string.action_retake_whorl : R.string.action_retake;
            case MONITOR -> R.string.action_monitor;
            case WHORL_PHOTO -> R.string.action_whorl_photo;
            case CHECK_NEARBY -> R.string.action_check_nearby;
            case RECHECK -> R.string.action_recheck;
            case EXTENSION -> R.string.action_extension;
        };
    }

    private void choose(int label, String[] values, java.util.function.IntConsumer chosen) {
        new AlertDialog.Builder(this).setTitle(t(label))
                .setItems(values, (dialog, which) -> chosen.accept(which)).show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card(int color) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(rounded(color));
        add(content, card, 12, -2);
        return card;
    }

    private void heading(LinearLayout card, int label) {
        TextView title = text(t(label), 18, INK, true);
        title.setAccessibilityHeading(true);
        card.addView(title);
    }

    private void add(LinearLayout parent, View child, int top, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button button(String label, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30236344),
                rounded(primary ? GREEN : 0xffedf2e8), null));
        button.setEnabled(!busy);
        button.setAlpha(busy ? .65f : 1f);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String t(int id) {
        return languageContext.getString(id);
    }
}

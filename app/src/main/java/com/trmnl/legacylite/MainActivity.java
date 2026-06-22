package com.trmnl.legacylite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

public class MainActivity extends Activity {
  private static final String FALLBACK_IMAGE_URL = "https://trmnl.com/images/system_screens/setup_logo/og_plus.png";

  private static final boolean ENABLE_FULL_REFRESH_CYCLE = true;
  private static final long FULL_REFRESH_BLACK_HOLD_MS = 1000;
  private static final long FULL_REFRESH_WHITE_HOLD_MS = 1000;
  private static final int FULL_REFRESH_CYCLES = 3;

  private static final int CONFIG_REQUEST = 1;

  private Prefs prefs;
  private ApiClient api;
  private ImageView image;
  private TextView status;
  private Bitmap lastBitmap;

  private final Handler h = new Handler(Looper.getMainLooper());
  private int refreshSec = 60;
  private int consecutiveImageFailures = 0;

  @Override protected void onCreate(Bundle b){
    super.onCreate(b);
    setContentView(R.layout.activity_main);
    prefs = new Prefs(this);
    api = new ApiClient();
    image = findViewById(R.id.fullImage);
    status = findViewById(R.id.statusText);
    applyConfiguredOrientation();
    immersive();

    image.setOnClickListener(v -> showMenu());
  }

  @Override protected void onResume(){
    super.onResume();
    if(!prefs.configured()) openConfig();
    else loadNext();
  }

  @Override protected void onPause(){
    super.onPause();
    h.removeCallbacksAndMessages(null);
  }

  private void openConfig(){
    startActivityForResult(new Intent(this, ConfigActivity.class), CONFIG_REQUEST);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data){
    super.onActivityResult(requestCode, resultCode, data);
    if(requestCode == CONFIG_REQUEST){
      if(prefs.configured()){
        Intent intent = getIntent();
        finish();
        startActivity(intent);
      } else {
        openConfig();
      }
    }
  }

  private void loadNext(){
    status.setVisibility(View.GONE);
    api.getDisplay(prefs.effectiveBase(), prefs.token(), r -> runOnUiThread(() -> applyResult(r)));
  }

  private void loadCurrentWithFullRefresh(){
    status.setVisibility(View.GONE);
    api.getCurrent(prefs.effectiveBase(), prefs.token(), r -> runOnUiThread(() -> {
      if (r.ok && r.bitmap != null) {
        Runnable renderTarget = () -> {
          image.setImageBitmap(r.bitmap);
          lastBitmap = r.bitmap;
          refreshSec = r.refreshRate > 0 ? r.refreshRate : 60;
          status.setText("");
          status.setVisibility(View.GONE);
          consecutiveImageFailures = 0;
          scheduleNext(Math.max(15, refreshSec));
        };

        if (ENABLE_FULL_REFRESH_CYCLE) {
          runFullRefreshCycle(renderTarget);
        } else {
          renderTarget.run();
        }
      } else {
        applyResult(r);
      }
    }));
  }

  private void runFullRefreshCycle(Runnable onComplete){
    runFullRefreshPhase(0, onComplete);
  }

  private void runFullRefreshPhase(int phaseIndex, Runnable onComplete){
    int totalPhases = FULL_REFRESH_CYCLES * 2;
    if (phaseIndex >= totalPhases) {
      onComplete.run();
      return;
    }

    boolean blackPhase = (phaseIndex % 2 == 0);
    image.setImageDrawable(new ColorDrawable(blackPhase ? Color.BLACK : Color.WHITE));
    long hold = blackPhase ? FULL_REFRESH_BLACK_HOLD_MS : FULL_REFRESH_WHITE_HOLD_MS;
    h.postDelayed(() -> runFullRefreshPhase(phaseIndex + 1, onComplete), hold);
  }

  private void debugTriggerFullRefreshCycle(){
    Bitmap previous = lastBitmap;
    runFullRefreshCycle(() -> {
      if (previous != null) {
        image.setImageBitmap(previous);
      }
      status.setText("Debug full-refresh cycle completed");
      status.setVisibility(View.VISIBLE);
      h.postDelayed(() -> status.setVisibility(View.GONE), 1500);
    });
  }

  private void applyResult(ApiClient.Result r){
    if(r.ok && r.bitmap!=null){
      image.setImageBitmap(r.bitmap);
      lastBitmap = r.bitmap;
      refreshSec = r.refreshRate>0 ? r.refreshRate : 60;
      status.setText("");
      status.setVisibility(View.GONE);
      consecutiveImageFailures = 0;
      scheduleNext(Math.max(15, refreshSec));
      return;
    }

    if(r.networkError){
      status.setText(r.message==null?"Network connectivity issue":r.message);
      status.setVisibility(View.VISIBLE);
      scheduleNext(30);
      return;
    }

    consecutiveImageFailures++;
    status.setText(r.message==null?"Image unavailable from server":r.message);
    status.setVisibility(View.VISIBLE);
    showFallbackThenHandlePersistence();
  }

  private void showFallbackThenHandlePersistence(){
    api.fetchImageByUrl(FALLBACK_IMAGE_URL, fr -> runOnUiThread(() -> {
      if(fr.ok && fr.bitmap!=null){
        image.setImageBitmap(fr.bitmap);
        lastBitmap = fr.bitmap;
      }
      h.removeCallbacksAndMessages(null);
      h.postDelayed(() -> {
        if(consecutiveImageFailures >= 2){
          prefs.clearConfigured();
          openConfig();
        } else {
          loadCurrentWithFullRefresh();
        }
      }, 30000);
    }));
  }

  private void scheduleNext(int seconds){
    h.removeCallbacksAndMessages(null);
    h.postDelayed(this::loadNext, seconds*1000L);
  }

  private void showMenu(){
    String[] options = new String[]{
        "Configure Device",
        "Refresh Current Image",
        "Load Next Playlist Image",
        "Debug Full Refresh Cycle"
    };

    new AlertDialog.Builder(this)
        .setTitle("Device Options")
        .setItems(options, (dialog, which) -> {
          if(which == 0){
            openConfig();
          } else if(which == 1){
            loadCurrentWithFullRefresh();
          } else if(which == 2){
            loadNext();
          } else if(which == 3){
            debugTriggerFullRefreshCycle();
          }
        })
        .show();
  }

  private void applyConfiguredOrientation(){
    String o = prefs.orientation();
    if (Prefs.ORIENTATION_LANDSCAPE.equals(o)) {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    } else {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }
  }

  @SuppressWarnings("deprecation")
  private void immersive(){
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    if (Build.VERSION.SDK_INT >= 11) {
      ImmersiveHelper.apply(getWindow().getDecorView());
    }
  }

  // Isolated in a separate class to avoid VerifyError on API < 11
  private static class ImmersiveHelper {
    @SuppressWarnings("deprecation")
    static void apply(View decor) {
      int flags = 0;
      if (Build.VERSION.SDK_INT >= 14) {
        flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
      }
      if (Build.VERSION.SDK_INT >= 16) {
        flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
      }
      if (Build.VERSION.SDK_INT >= 19) {
        flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
      }
      decor.setSystemUiVisibility(flags);
    }
  }
}

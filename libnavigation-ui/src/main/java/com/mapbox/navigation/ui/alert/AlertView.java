package com.mapbox.navigation.ui.alert;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.mapbox.libnavigation.ui.R;

/**
 * A custom View that can show a quick message to a user.
 * <p>
 * Accompanied with a set duration (in millis), the View will automatically dismiss itself
 * after the duration countdown has completed.
 */
public class AlertView extends CardView {

  private static final String ALERT_VIEW_PROGRESS = "progress";
  private TextView alertText;
  private ProgressBar alertProgressBar;

  private Animation fadeOut;
  private Animation slideDownTop;

  private int backgroundColor;
  private int progressBarBackgroundColor;
  private int progressBarColor;
  private int textColor;

  public AlertView(Context context) {
    this(context, null);
  }

  public AlertView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, -1);
  }

  public AlertView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initCustomizedAttributes(attrs);
    init();
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    bind();
    applyCustomizedAttributes();
    initAnimations();
  }

  /**
   * Animates the View from top down to its set position.
   * <p>
   * If a non-null duration is passed, a countdown loading bar will show to
   * indicate the View will be automatically dismissed if not clicked.
   *
   * @param alertText   text to be shown in the View
   * @param duration    in milliseconds, how long the view will be shown
   * @param showLoading true if should show the progress bar, false if not
   */
  public void show(String alertText, long duration, boolean showLoading) {
    this.alertText.setText(alertText);
    alertProgressBar.setProgress(alertProgressBar.getMax());
    // Start animation based on current visibility
    if (getVisibility() == INVISIBLE) {
      setVisibility(VISIBLE);
      startAnimation(slideDownTop);

      // If a duration is found, start the countdown
      if (duration > 0L) {
        startCountdown(duration);
      }
      // Show / hide loading
      showLoading(showLoading);
    }
  }

  /**
   * Hides the View with a slide up animation if the View is currently VISIBLE.
   *
   * @since 0.7.0
   */
  public void hide() {
    if (getVisibility() == VISIBLE) {
      startAnimation(fadeOut);
      setVisibility(INVISIBLE);
    }
  }

  /**
   * Returns the current text being shown by the {@link AlertView}.
   *
   * @return current text in alertText {@link TextView}
   * @since 0.7.0
   */
  public String getAlertText() {
    return alertText.getText().toString();
  }

  private void init() {
    inflate(getContext(), R.layout.alert_view_layout, this);
  }

  private void bind() {
    alertText = findViewById(R.id.alertText);
    alertProgressBar = findViewById(R.id.alertProgressBar);
  }

  private void initCustomizedAttributes(AttributeSet attributeSet) {
    TypedArray typedArray = getContext().obtainStyledAttributes(attributeSet, R.styleable.AlertView);
    backgroundColor = ContextCompat.getColor(getContext(),
      typedArray.getResourceId(R.styleable.AlertView_alertViewBackgroundColor,
        R.color.mapbox_alert_view_background));
    progressBarBackgroundColor = ContextCompat.getColor(getContext(),
      typedArray.getResourceId(R.styleable.AlertView_alertViewProgressBarBackgroundColor,
        R.color.mapbox_alert_view_progress_bar_background));
    progressBarColor = ContextCompat.getColor(getContext(),
      typedArray.getResourceId(R.styleable.AlertView_alertViewProgressBarColor,
        R.color.mapbox_alert_view_progress_bar));
    textColor = ContextCompat.getColor(getContext(),
      typedArray.getResourceId(R.styleable.AlertView_alertViewTextColor,
        R.color.mapbox_alert_view_text));

    typedArray.recycle();
  }

  private void initAnimations() {
    fadeOut = new AlphaAnimation(1, 0);
    fadeOut.setInterpolator(new AccelerateInterpolator());
    fadeOut.setDuration(300);
    slideDownTop = AnimationUtils.loadAnimation(getContext(), R.anim.slide_down_top);
    slideDownTop.setInterpolator(new OvershootInterpolator(2.0f));
  }

  private void applyCustomizedAttributes() {
    CardView alertCardView = findViewById(R.id.alertCardView);
    alertCardView.setCardBackgroundColor(backgroundColor);
    alertText.setTextColor(textColor);

    LayerDrawable progressBarDrawable = (LayerDrawable) alertProgressBar.getProgressDrawable();
    // ProgressBar progress color
    Drawable progressBackgroundDrawable = progressBarDrawable.getDrawable(0);
    progressBackgroundDrawable.setColorFilter(progressBarBackgroundColor, PorterDuff.Mode.SRC_IN);

    // ProgressBar background color
    Drawable progressDrawable = progressBarDrawable.getDrawable(1);
    progressDrawable.setColorFilter(progressBarColor, PorterDuff.Mode.SRC_IN);

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      // Hide the background
      getBackground().setAlpha(0);
    } else {
      setBackgroundColor(ContextCompat.getColor(getContext(), android.R.color.transparent));
    }
  }

  private void startCountdown(long duration) {
    ObjectAnimator countdownAnimation = ObjectAnimator.ofInt(alertProgressBar, ALERT_VIEW_PROGRESS, 0);
    countdownAnimation.setInterpolator(new LinearInterpolator());
    countdownAnimation.setDuration(duration);
    countdownAnimation.addListener(new AlertViewAnimatorListener(this));
    countdownAnimation.start();
  }

  private void showLoading(boolean showLoading) {
    alertProgressBar.setVisibility(showLoading ? VISIBLE : INVISIBLE);
  }
}

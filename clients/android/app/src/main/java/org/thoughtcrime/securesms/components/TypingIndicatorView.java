package org.thoughtcrime.securesms.components;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.thoughtcrime.securesms.R;

/**
 * Asher typing indicator: three 6dp dots. Each dot rises 3dp and brightens from the muted text colour
 * to the rim colour over 900ms (ease-in-out), staggered 150ms, and loops.
 */
public class TypingIndicatorView extends LinearLayout {

  private static final long         DOT_DURATION   = 900;
  private static final long         DOT_STAGGER    = 150;
  private static final long         CYCLE_DURATION = DOT_DURATION + 2 * DOT_STAGGER;
  private static final float        RISE_DP        = 3f;
  private static final Interpolator EASE_IN_OUT    = new PathInterpolator(0.65f, 0f, 0.35f, 1f);

  private final ArgbEvaluator colorEvaluator = new ArgbEvaluator();

  private boolean isActive;
  private long    startTime;
  private int     dimTint;
  private int     brightTint;
  private float   risePx;

  private View dot1;
  private View dot2;
  private View dot3;

  public TypingIndicatorView(Context context) {
    super(context);
    initialize(null);
  }

  public TypingIndicatorView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize(attrs);
  }

  private void initialize(@Nullable AttributeSet attrs) {
    inflate(getContext(), R.layout.typing_indicator_view, this);

    setWillNotDraw(false);

    dot1   = findViewById(R.id.typing_dot1);
    dot2   = findViewById(R.id.typing_dot2);
    dot3   = findViewById(R.id.typing_dot3);
    risePx = RISE_DP * getResources().getDisplayMetrics().density;

    dimTint    = ContextCompat.getColor(getContext(), R.color.asher_typing_dot_dim);
    brightTint = ContextCompat.getColor(getContext(), R.color.asher_typing_dot_bright);

    if (attrs != null) {
      TypedArray typedArray = getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.TypingIndicatorView, 0, 0);
      int        tint       = typedArray.getColor(R.styleable.TypingIndicatorView_typingIndicator_tint, brightTint);
      typedArray.recycle();

      setDotTint(tint);
    } else {
      setDotTint(brightTint);
    }
  }

  /**
   * @param tint The colour a dot brightens to at the top of its rise. The resting colour is the
   *             muted text token, or a 40% blend of the tint when it is not the default.
   */
  public void setDotTint(@ColorInt int tint) {
    brightTint = tint;
    dimTint    = tint == ContextCompat.getColor(getContext(), R.color.asher_typing_dot_bright)
                 ? ContextCompat.getColor(getContext(), R.color.asher_typing_dot_dim)
                 : (int) colorEvaluator.evaluate(0.4f, ContextCompat.getColor(getContext(), R.color.asher_typing_dot_dim), tint);

    renderDefault(dot1);
    renderDefault(dot2);
    renderDefault(dot3);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (!isActive) {
      super.onDraw(canvas);
      return;
    }

    long timeInCycle = (System.currentTimeMillis() - startTime) % CYCLE_DURATION;

    render(dot1, timeInCycle, 0);
    render(dot2, timeInCycle, DOT_STAGGER);
    render(dot3, timeInCycle, 2 * DOT_STAGGER);

    super.onDraw(canvas);
    postInvalidate();
  }

  private void render(View dot, long timeInCycle, long start) {
    long end = start + DOT_DURATION;

    if (timeInCycle < start || timeInCycle > end) {
      renderDefault(dot);
      return;
    }

    float progress = (float) (timeInCycle - start) / DOT_DURATION;
    float phase    = progress < 0.5f ? EASE_IN_OUT.getInterpolation(progress * 2f)
                                     : EASE_IN_OUT.getInterpolation(2f - progress * 2f);

    dot.setTranslationY(-risePx * phase);
    applyTint(dot, (int) colorEvaluator.evaluate(phase, dimTint, brightTint));
  }

  private void renderDefault(View dot) {
    dot.setTranslationY(0f);
    dot.setAlpha(1f);
    dot.setScaleX(1f);
    dot.setScaleY(1f);
    applyTint(dot, dimTint);
  }

  private void applyTint(View dot, @ColorInt int color) {
    if (dot.getBackground() != null) {
      dot.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }
  }

  public void startAnimation() {
    isActive  = true;
    startTime = System.currentTimeMillis();

    postInvalidate();
  }

  public void stopAnimation() {
    isActive = false;
    renderDefault(dot1);
    renderDefault(dot2);
    renderDefault(dot3);
  }

  public boolean isActive() {
    return isActive;
  }
}

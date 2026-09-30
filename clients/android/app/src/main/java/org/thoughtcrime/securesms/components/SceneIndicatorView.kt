/*
 * Asher design system: scene indicator.
 */

package org.thoughtcrime.securesms.components

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.thoughtcrime.securesms.R

/**
 * A small pill under the conversation title that says how the two of you are connected right now:
 * Orbit (internet), Mesh (radio), Carrying (queued for a relay) or Out of range. A 6dp dot with a soft
 * glow of the same colour breathes on a 2s cycle while a link is active.
 *
 * Only Orbit / Out of range are wired today (from connectivity); the mesh transport sets the other
 * states through [setState].
 */
class SceneIndicatorView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

  enum class State(@StringRes val label: Int, @ColorRes val color: Int, val breathes: Boolean) {
    ORBIT(R.string.SceneIndicator__orbit, R.color.asher_scene_orbit, true),
    MESH(R.string.SceneIndicator__mesh, R.color.asher_scene_mesh, true),
    CARRYING(R.string.SceneIndicator__carrying, R.color.asher_scene_carrying, true),
    OUT_OF_RANGE(R.string.SceneIndicator__out_of_range, R.color.asher_scene_offline, false)
  }

  private val dot: ImageView
  private val label: TextView
  private var pulse: ObjectAnimator? = null

  var state: State = State.OUT_OF_RANGE
    private set

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    LayoutInflater.from(context).inflate(R.layout.scene_indicator_view, this, true)
    setBackgroundResource(R.drawable.asher_scene_pill_bg)
    dot = findViewById(R.id.scene_indicator_dot)
    label = findViewById(R.id.scene_indicator_label)
    if (!isInEditMode) {
      setState(state)
    }
  }

  fun setState(state: State) {
    this.state = state
    label.setText(state.label)
    tintDot(ContextCompat.getColor(context, state.color))
    contentDescription = label.text
    updatePulse()
  }

  /** For "Mesh · N hops" style detail supplied by the transport. */
  fun setState(state: State, detail: CharSequence?) {
    setState(state)
    if (!detail.isNullOrBlank()) {
      label.text = context.getString(R.string.SceneIndicator__label_with_detail, context.getString(state.label), detail)
      contentDescription = label.text
    }
  }

  private fun tintDot(color: Int) {
    val layers = dot.drawable?.mutate() as? LayerDrawable ?: return
    (layers.findDrawableByLayerId(R.id.scene_indicator_glow) as? GradientDrawable)?.setColor(ColorUtils.setAlphaComponent(color, GLOW_ALPHA))
    (layers.findDrawableByLayerId(R.id.scene_indicator_core) as? GradientDrawable)?.setColor(color)
  }

  private fun updatePulse() {
    pulse?.cancel()
    pulse = null
    dot.alpha = 1f

    if (!state.breathes || !animationsEnabled()) {
      return
    }

    pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.55f).apply {
      duration = BREATH_HALF_CYCLE_MS
      repeatMode = ValueAnimator.REVERSE
      repeatCount = ValueAnimator.INFINITE
      interpolator = EASE_IN_OUT
      start()
    }
  }

  private fun animationsEnabled(): Boolean {
    return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    updatePulse()
  }

  override fun onDetachedFromWindow() {
    pulse?.cancel()
    pulse = null
    super.onDetachedFromWindow()
  }

  companion object {
    private const val BREATH_HALF_CYCLE_MS = 1000L
    private const val GLOW_ALPHA = 0x59
    private val EASE_IN_OUT = PathInterpolator(0.65f, 0f, 0.35f, 1f)
  }
}

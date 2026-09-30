package org.thoughtcrime.securesms.wallpaper

import android.content.Context
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.widget.ImageView
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.model.databaseprotos.Wallpaper

/**
 * Asher's built-in wallpaper: Earth from low orbit (res/drawable-nodpi/asher_earth.webp).
 *
 * It is the look of a fresh install: shown behind every conversation that has no wallpaper of its own
 * and no global wallpaper set, dimmed to 40% in dark mode. It is never persisted, so the user's
 * wallpaper settings still read as "none" and picking any wallpaper replaces it.
 */
class AsherEarthWallpaper private constructor() : ChatWallpaper, Parcelable {

  override fun getDimLevelForDarkTheme(): Float = DIM_LEVEL_DARK

  override fun isPhoto(): Boolean = true

  override fun loadInto(imageView: ImageView) {
    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    imageView.setImageResource(R.drawable.asher_earth)
  }

  override fun prefetch(context: Context, maxWaitTime: Long): Boolean = true

  override fun isPrefetched(): Boolean = true

  override fun isSameSource(chatWallpaper: ChatWallpaper): Boolean = chatWallpaper is AsherEarthWallpaper

  /** Only reached if something serializes the default; it is never stored by the app itself. */
  override fun serialize(): Wallpaper {
    return Wallpaper.Builder()
      .file_(Wallpaper.File.Builder().uri(resourceUri().toString()).build())
      .dimLevelInDarkTheme(DIM_LEVEL_DARK)
      .build()
  }

  /** A stable marker for the built-in wallpaper; not a loadable location. */
  fun resourceUri(): Uri = Uri.parse(MARKER_URI)

  override fun describeContents(): Int = 0

  override fun writeToParcel(dest: Parcel, flags: Int) = Unit

  override fun equals(other: Any?): Boolean = other is AsherEarthWallpaper

  override fun hashCode(): Int = AsherEarthWallpaper::class.java.hashCode()

  companion object {
    /** ~40% dim in dark mode, per the design tokens. */
    const val DIM_LEVEL_DARK = 0.4f

    private const val MARKER_URI = "asher://wallpaper/earth"

    @JvmField
    val INSTANCE = AsherEarthWallpaper()

    @JvmField
    val CREATOR: Parcelable.Creator<AsherEarthWallpaper> = object : Parcelable.Creator<AsherEarthWallpaper> {
      override fun createFromParcel(source: Parcel): AsherEarthWallpaper = INSTANCE
      override fun newArray(size: Int): Array<AsherEarthWallpaper?> = arrayOfNulls(size)
    }
  }
}

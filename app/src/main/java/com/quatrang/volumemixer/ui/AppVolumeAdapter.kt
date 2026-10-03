package com.quatrang.volumemixer.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.quatrang.volumemixer.R
import com.quatrang.volumemixer.engine.AppAudio

/** List of apps with a volume slider + mute button. Used by the main screen and the floating panel. */
class AppVolumeAdapter(
    private val compact: Boolean,
    private val listener: Listener
) : ListAdapter<AppAudio, AppVolumeAdapter.VH>(DIFF) {

    interface Listener {
        fun onVolumeChanged(app: AppAudio, volume: Int)
        fun onToggleMute(app: AppAudio)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        val sub: TextView = view.findViewById(R.id.app_sub)
        val percent: TextView = view.findViewById(R.id.app_percent)
        val mute: ImageButton = view.findViewById(R.id.app_mute)
        val seek: SeekBar = view.findViewById(R.id.app_seek)
        var item: AppAudio? = null
        var tracking = false

        init {
            seek.max = 100
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val app = item ?: return
                    percent.text = percentText(sb.context, progress, false)
                    seek.alpha = 1f
                    listener.onVolumeChanged(app, progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    tracking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    tracking = false
                    val app = item ?: return
                    listener.onVolumeChanged(app, sb.progress)
                }
            })
            mute.setOnClickListener {
                item?.let { listener.onToggleMute(it) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (compact) R.layout.item_app_volume_compact else R.layout.item_app_volume
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        val ctx = holder.itemView.context
        holder.item = app
        holder.label.text = app.label
        holder.icon.setImageDrawable(IconCache.get(ctx, app.packageName))
        holder.sub.text = when {
            app.playing -> ctx.getString(R.string.app_state_playing)
            app.active -> ctx.getString(R.string.app_state_paused)
            else -> ctx.getString(R.string.app_state_saved)
        }
        holder.sub.isActivated = app.playing
        if (!holder.tracking) {
            holder.seek.progress = app.volume
            holder.percent.text = percentText(ctx, app.volume, app.muted)
        }
        holder.seek.alpha = if (app.muted) 0.4f else 1f
        holder.mute.setImageResource(if (app.muted || app.volume == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        holder.mute.contentDescription = ctx.getString(if (app.muted) R.string.action_unmute else R.string.action_mute)
        holder.mute.isActivated = app.muted
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppAudio>() {
            override fun areItemsTheSame(a: AppAudio, b: AppAudio) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppAudio, b: AppAudio) = a == b
        }

        fun percentText(ctx: Context, volume: Int, muted: Boolean): String =
            if (muted) ctx.getString(R.string.muted_short) else "$volume%"
    }
}

/** Caches app icons so scrolling / frequent refreshes stay smooth. */
object IconCache {
    private val cache = LruCache<String, Drawable>(64)

    fun get(ctx: Context, pkg: String): Drawable? {
        val cached = cache.get(pkg) ?: run {
            val d = try {
                ctx.packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
                ctx.getDrawable(R.mipmap.ic_launcher)
            } ?: return null
            cache.put(pkg, d)
            d
        }
        // Each ImageView gets its own instance (the same icon can be shown in the app and the floating panel).
        return cached.constantState?.newDrawable(ctx.resources)?.mutate() ?: cached
    }
}

/** RecyclerView that wraps its content but never grows taller than [maxHeightPx]. */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var maxHeightPx: Int = (320 * context.resources.displayMetrics.density).toInt()
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

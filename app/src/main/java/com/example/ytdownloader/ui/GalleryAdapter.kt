package com.example.ytdownloader.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.ytdownloader.data.DownloadedMedia
import com.example.ytdownloader.databinding.ItemGalleryBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class GalleryAdapter(
    private val onOpen: (DownloadedMedia) -> Unit,
    private val onLongPress: (DownloadedMedia) -> Unit
) : ListAdapter<DownloadedMedia, GalleryAdapter.VH>(DIFF) {

    fun submit(items: List<DownloadedMedia>) = submitList(items)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemGalleryBinding) : RecyclerView.ViewHolder(b.root) {

        @SuppressLint("SetTextI18n")
        fun bind(item: DownloadedMedia) {
            b.tvName.text = item.displayName

            val details = buildList {
                add(formatSize(item.sizeBytes))
                if (item.durationMs > 0) add(formatDuration(item.durationMs))
            }
            b.tvMeta.text = details.joinToString(" · ")

            b.tvBadge.visibility = if (item.isAudio) View.VISIBLE else View.GONE
            b.tvBadge.text = item.displayName.substringAfterLast('.', "").uppercase(Locale.US)

            Glide.with(b.ivThumb)
                .load(item.uri)
                .centerCrop()
                .placeholder(ColorDrawable(Color.LTGRAY))
                .into(b.ivThumb)

            b.root.setOnClickListener { onOpen(item) }
            b.root.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "—"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
            else String.format(Locale.US, "%.0f МБ", mb)
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadedMedia>() {
            override fun areItemsTheSame(a: DownloadedMedia, b: DownloadedMedia) = a.uri == b.uri
            override fun areContentsTheSame(a: DownloadedMedia, b: DownloadedMedia) = a == b
        }
    }
}

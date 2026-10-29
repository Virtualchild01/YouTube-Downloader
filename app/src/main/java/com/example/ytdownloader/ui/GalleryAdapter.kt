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
import com.example.ytdownloader.util.Formatters
import java.util.Locale

class GalleryAdapter(
    private val onOpen: (DownloadedMedia) -> Unit,
    private val onMore: (DownloadedMedia) -> Unit
) : ListAdapter<DownloadedMedia, GalleryAdapter.VH>(DIFF) {

    fun submit(items: List<DownloadedMedia>) = submitList(items)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemGalleryBinding) : RecyclerView.ViewHolder(b.root) {

        @SuppressLint("SetTextI18n")
        fun bind(item: DownloadedMedia) {
            val context = b.root.context
            b.tvName.text = item.displayName

            b.tvMeta.text = buildList {
                add(Formatters.size(context, item.sizeBytes))
                if (item.durationMs > 0) add(Formatters.timecode(item.durationMs))
            }.joinToString(" · ")

            b.tvBadge.visibility = if (item.isAudio) View.VISIBLE else View.GONE
            b.tvBadge.text = item.displayName.substringAfterLast('.', "").uppercase(Locale.US)

            Glide.with(b.ivThumb)
                .load(item.uri)
                .centerCrop()
                .placeholder(ColorDrawable(Color.LTGRAY))
                .into(b.ivThumb)

            b.root.setOnClickListener { onOpen(item) }

            // Точки над превью открывают меню «Поделиться / Удалить».
            // Раньше удаление жило на долгом нажатии по карточке — жест,
            // который никто не находил; видимая кнопка честнее.
            b.btnMore.setOnClickListener { onMore(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadedMedia>() {
            override fun areItemsTheSame(a: DownloadedMedia, b: DownloadedMedia) = a.uri == b.uri
            override fun areContentsTheSame(a: DownloadedMedia, b: DownloadedMedia) = a == b
        }
    }
}

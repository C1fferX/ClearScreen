package com.clearscreen.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.clearscreen.app.R
import com.clearscreen.app.data.db.InterceptRecord
import com.clearscreen.app.databinding.ItemAppStatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsAdapter : ListAdapter<InterceptRecord, StatsAdapter.ViewHolder>(DiffCallback) {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    object DiffCallback : DiffUtil.ItemCallback<InterceptRecord>() {
        override fun areItemsTheSame(oldItem: InterceptRecord, newItem: InterceptRecord): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: InterceptRecord, newItem: InterceptRecord): Boolean {
            return oldItem == newItem
        }
    }

    inner class ViewHolder(private val binding: ItemAppStatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: InterceptRecord) {
            binding.tvAppName.text = record.appName
            binding.tvPackageName.text = record.packageName
            binding.tvInterceptCount.text = record.interceptCount.toString()

            val formattedTime = timeFormat.format(Date(record.lastInterceptTime))
            val detailText = if (record.jumpInterceptCount > 0) {
                "最近: $formattedTime (含误触拦截 ${record.jumpInterceptCount} 次)"
            } else {
                "最近: $formattedTime"
            }
            binding.tvLastInterceptTime.text = detailText

            // 异步加载应用图标
            val icon = getAppIcon(binding.root.context, record.packageName)
            binding.ivAppIcon.setImageDrawable(icon)
        }

        private fun getAppIcon(context: Context, packageName: String): Drawable {
            return try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppStatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

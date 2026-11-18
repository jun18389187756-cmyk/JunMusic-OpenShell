package com.jun.musiccloud.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.Player
import com.jun.musiccloud.MainActivity
import com.jun.musiccloud.R

/**
 * 桌面 4×4 播放控制小组件（大尺寸）。
 *
 * 与 4×2 一样：Android 16 下不能在 BroadcastReceiver 里 bindService，
 * 也不能把 http(s) 封面 URI 放进 RemoteViews。这里只读播放服务写好的快照，
 * 封面用本地缓存的 Bitmap。
 */
class MusicWidget4x4 : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val snap = WidgetState.snapshot(context)
        // 与 4×2 同一条规则：内容没变 / 距上次重绘太近就跳过，
        // 避免在广播主线程上反复解码封面（老机广播超时 → ANR → 进程被收）。
        if (!WidgetState.shouldRender(TAG, WidgetState.renderSignature(snap, context))) return
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, snap))
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidget4x4::class.java))
        val snap = WidgetState.snapshot(context)
        // 刚添加这一次必须画出来：绕过节流
        ids.forEach { manager.updateAppWidget(it, buildViews(context, snap)) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }

    companion object {
        private const val ACTION_PREV = "com.jun.musiccloud.widget4x4.PREV"
        private const val ACTION_PLAY = "com.jun.musiccloud.widget4x4.PLAY"
        private const val ACTION_NEXT = "com.jun.musiccloud.widget4x4.NEXT"

        /** 节流记录的分组名：与 4×2 各自一条记录，互不遮挡 */
        private const val TAG = "widget_4x4"

        fun updateAll(context: Context, player: Player? = null) {
            if (player != null) WidgetState.update(context, player)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidget4x4::class.java))
            if (ids.isEmpty()) return
            val snap = WidgetState.snapshot(context)
            if (!WidgetState.shouldRender(TAG, WidgetState.renderSignature(snap, context))) return
            ids.forEach { manager.updateAppWidget(it, buildViews(context, snap)) }
        }

        private fun buildViews(context: Context, snap: WidgetSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.music_widget_4x4)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_cover, openPi)
            views.setOnClickPendingIntent(R.id.widget_title, openPi)
            views.setOnClickPendingIntent(R.id.widget_artist, openPi)

            views.setOnClickPendingIntent(R.id.widget_prev, controlPi(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widget_play, controlPi(context, ACTION_PLAY))
            views.setOnClickPendingIntent(R.id.widget_next, controlPi(context, ACTION_NEXT))

            views.setImageViewResource(
                R.id.widget_play,
                if (snap.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            )
            views.setTextViewText(R.id.widget_title, snap.title)
            views.setTextViewText(R.id.widget_artist, snap.artist)
            views.setTextViewText(R.id.widget_next_song, snap.nextTitle)

            val cover = WidgetState.loadCover(context)
            if (cover != null) {
                views.setImageViewBitmap(R.id.widget_cover, cover)
            } else {
                views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
            }
            return views
        }

        private fun controlPi(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MusicWidget4x4::class.java).apply {
                this.action = action
                // 只投递给本包：省得别的 App 声明同名 receiver 蹭到这条 PendingIntent
                setPackage(context.packageName)
                // 随包带上本次安装的私有令牌：onReceive 只认「令牌对得上 + 没有外来发送包名」的控制指令
                putExtra(EXTRA_WIDGET_TOKEN, WidgetGuard.token(context))
            }
            // getBroadcast 只有 flags / Bundle options 两个版本，没有「要求接收方持权限」的重载，
            // 所以来源认证放在 Intent 里的令牌上，判据见 WidgetGuard。
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        // 6.2：控制类动作必须先认来路。AppWidgetProvider 的标准动作
        // （APPWIDGET_UPDATE / ENABLED / DISABLED / DELETED）由桌面发出，不在这条判断里，
        // 小组件刷新照旧；只有我们自己那三条按钮动作要过 WidgetGuard 的令牌 + 发送包名检查。
        if ((action == ACTION_PREV || action == ACTION_PLAY || action == ACTION_NEXT) &&
            !WidgetGuard.allowed(context, intent, widgetSentFromPackage())
        ) {
            return
        }
        val service = MusicService.instance
        if (service != null) {
            when (action) {
                ACTION_PREV -> service.widgetPrevious()
                ACTION_PLAY -> service.widgetToggle()
                ACTION_NEXT -> service.widgetNext()
            }
            return
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("widget_action", action)
        }
        runCatching { context.startActivity(open) }
    }
}

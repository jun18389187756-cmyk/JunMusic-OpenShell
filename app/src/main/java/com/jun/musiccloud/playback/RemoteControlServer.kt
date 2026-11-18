package com.jun.musiccloud.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.jun.musiccloud.data.LyricParser
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * 局域网遥控 / 歌词投屏。
 *
 * 播放服务在后台起一个 NanoHTTPD（0.0.0.0:8099），同一 Wi-Fi 下的手机/电脑
 * 用浏览器打开 `http://<电脑IP>:8099/?t=<token>` 就能控制播放、看当前歌词。
 * token 首次开启时随机生成并持久化，URL 不带 token 一律 403。
 *
 * 所有播放器操作都 post 到主线程：Media3 的 Player 不是线程安全的。
 */
class RemoteControlServer(
    private val playerProvider: () -> Player?,
    port: Int = PORT
) : NanoHTTPD("0.0.0.0", port) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lyricKey: String = ""
    @Volatile private var lyricDuration: Long = 0L
    @Volatile private var lyricLines: List<com.jun.musiccloud.data.LyricLine> = emptyList()

    override fun serve(session: IHTTPSession): Response {
        // 请求来自局域网里的任意客户端，参数完全不可信；stateJson 也要读播放状态和歌词文件。
        // 任何未捕获的异常都从 HTTP 工作线程抛出去，最后会变成"服务进程没了"，
        // 在老机型上就是用户反馈的"开着遥控页面手机重启了"。这里一律降级成 500。
        return try {
            handle(session)
        } catch (t: Throwable) {
            android.util.Log.w("RemoteControlServer", "serve failed: ${t.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "error"
            )
        }
    }

    private fun handle(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val token = session.parameters["t"]?.firstOrNull().orEmpty()
        val expected = ACCESS_TOKEN
        // 没初始化令牌时一律拒绝，避免空白 token 直接放行
        if (expected.isBlank() || token != expected) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/plain; charset=utf-8", "token 无效"
            )
        }
        return when (uri) {
            "/", "/index.html" -> newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", PAGE
            )
            "/api/state" -> newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", stateJson().toString()
            )
            "/api/toggle" -> command { p -> if (p.isPlaying) p.pause() else p.play() }
            "/api/next" -> command { p -> p.seekToNextMediaItem() }
            "/api/prev" -> command { p -> p.seekToPreviousMediaItem() }
            "/api/seek" -> {
                val ms = session.parameters["ms"]?.firstOrNull()?.toLongOrNull() ?: 0L
                command { p -> p.seekTo(ms.coerceAtLeast(0L)) }
            }
            "/api/volume" -> {
                val v = session.parameters["v"]?.firstOrNull()?.toFloatOrNull() ?: 1f
                command { p ->
                    val vol = v.coerceIn(0f, 1f)
                    // FadeController 是音量的唯一写入口，直接改 player.volume 会被 ticker 覆盖
                    com.jun.musiccloud.audio.FadeController.setUserGain(vol)
                    p.volume = vol
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")        }
    }

    private fun command(block: (Player) -> Unit): Response {
        main.post { playerProvider()?.let { runCatching { block(it) } } }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true}"
        )
    }

    private fun stateJson(): JSONObject {
        val s = NowPlayingBus.state.value
        val pos = if (s.playing && s.updatedAt > 0L) {
            s.positionMs + (System.currentTimeMillis() - s.updatedAt)
        } else s.positionMs
        val position = if (s.durationMs > 0) pos.coerceIn(0L, s.durationMs) else pos.coerceAtLeast(0L)
        val lines = ensureLyrics(s.lyricKey, s.durationMs)
        val idx = LyricParser.currentIndex(lines, position)
        val current = lines.getOrNull(idx)
        val line = current?.text.orEmpty()
        val next = lines.getOrNull(idx + 1)?.text.orEmpty()
        // 音量偶尔会是 NaN（FadeController 未就绪时除零），NaN 直接塞进 JSON 会写成
        // 非法的 "NaN" 字面量，遥控页 JSON.parse 整个失败 → 页面白屏。归一化成一个数。
        val vol = if (s.volume.isNaN() || s.volume.isInfinite()) 1f else s.volume.coerceIn(0f, 1f)
        // 遥控页要像歌词舞台一样有上下文，所以多给上一句、译文和再下一句。
        return JSONObject().apply {
            put("title", s.title)
            put("artist", s.artist)
            put("album", s.album)
            put("playing", s.playing)
            put("position", position)
            put("duration", s.durationMs)
            put("index", s.currentIndex)
            put("queue", s.queueSize)
            put("volume", vol)
            put("lyric", line)
            put("translation", current?.translation.orEmpty())
            put("prev", lines.getOrNull(idx - 1)?.text.orEmpty())
            put("prev2", lines.getOrNull(idx - 2)?.text.orEmpty())
            put("next", next)
            put("next2", lines.getOrNull(idx + 2)?.text.orEmpty())
        }
    }

    private fun ensureLyrics(key: String, duration: Long): List<com.jun.musiccloud.data.LyricLine> {
        if (key == lyricKey && duration == lyricDuration) return lyricLines
        lyricKey = key
        lyricDuration = duration
        lyricLines = if (key.isBlank()) emptyList()
        else runCatching { LyricParser.loadLyrics(key, duration) }.getOrDefault(emptyList())
        return lyricLines
    }

    companion object {
        const val PORT = 8099
        private const val PREFS = "jun_prefs"
        private const val KEY_TOKEN = "remote_control_token"

        @Volatile var ACCESS_TOKEN: String = ""
            private set

        @Volatile private var instance: RemoteControlServer? = null

        fun token(context: Context): String {
            if (ACCESS_TOKEN.isNotBlank()) return ACCESS_TOKEN
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_TOKEN, null)
            val t = saved?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().replace("-", "").take(10).also {
                    prefs.edit().putString(KEY_TOKEN, it).apply()
                }
            ACCESS_TOKEN = t
            return t
        }

        fun url(context: Context): String = "http://${localIp()}:$PORT/?t=${token(context)}"

        @Synchronized
        fun start(context: Context, playerProvider: () -> Player?) {
            if (instance != null) return
            // 先把令牌装进内存：服务可能在冷启动时被媒体键唤起，不能等设置页来触发
            token(context)
            val server = RemoteControlServer(playerProvider)
            runCatching {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                instance = server
            }.onFailure {
                android.util.Log.w("RemoteControl", "start failed: ${it.message}")
            }
        }

        @Synchronized
        fun stop() {
            runCatching { instance?.stop() }
            instance = null
        }

        val running: Boolean get() = instance != null

        /** 局域网 IPv4；没有 Wi-Fi 时回退 127.0.0.1（页面在本机可打开） */
        private fun localIp(): String {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList().asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull() ?: "127.0.0.1"
        }
    }

    private val PAGE = """
<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no">
<meta name="theme-color" content="#07080c">
<title>Jun Music · 遥控</title>
<style>
:root{--accent:#22d3ee;--accent2:#7c3aed;--bg:#07080c;--ink:#f7f8fb;--sub:#98a0ad;--panel:rgba(255,255,255,.055);--line:rgba(255,255,255,.13);--shadow:rgba(0,0,0,.5)}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{height:100%}
body{margin:0;background:var(--bg);color:var(--ink);overflow:hidden;
font-family:system-ui,-apple-system,"PingFang SC","Noto Sans SC",sans-serif;transition:background .45s,color .45s}
#bg{position:fixed;inset:0;width:100%;height:100%;display:block}
.shade{position:fixed;inset:0;pointer-events:none;
background:radial-gradient(120% 85% at 50% 8%,transparent 0%,rgba(0,0,0,.10) 58%,rgba(0,0,0,.46) 100%)}
main{position:relative;z-index:2;height:100svh;max-width:640px;margin:0 auto;
padding:max(16px,env(safe-area-inset-top)) 22px max(14px,env(safe-area-inset-bottom));
display:flex;flex-direction:column}
header{display:flex;align-items:center;justify-content:space-between;gap:12px}
.brand{display:flex;align-items:baseline;gap:8px;font-size:11px;letter-spacing:.22em;font-weight:800;color:var(--accent)}
.brand b{font-size:15px;letter-spacing:.08em;color:var(--ink)}
.brand span{color:var(--sub);font-weight:700;letter-spacing:.18em}
.stageName{font-size:10px;color:var(--accent);border:1px solid var(--line);border-radius:99px;padding:4px 9px;
letter-spacing:.12em;font-weight:700;margin-left:auto}
.conn{display:flex;align-items:center;gap:7px;font-size:11px;color:var(--sub);letter-spacing:.06em}
.conn i{width:7px;height:7px;border-radius:50%;background:var(--accent);box-shadow:0 0 10px var(--accent);animation:breath 2.4s ease-in-out infinite}
@keyframes breath{0%,100%{opacity:.45;transform:scale(.85)}50%{opacity:1;transform:scale(1.1)}}
.hero{flex:1;min-height:0;display:flex;flex-direction:column;justify-content:center;padding:18px 0 10px}
.meta{text-align:center;margin-bottom:clamp(18px,4vh,36px)}
.title{margin:0;font-size:clamp(20px,4.6vw,30px);line-height:1.2;font-weight:800;letter-spacing:-.01em;overflow-wrap:anywhere}
.artist{margin:7px 0 0;font-size:13px;color:var(--sub);letter-spacing:.04em;min-height:18px}
.lyricWrap{position:relative;text-align:center;min-height:clamp(120px,24vh,190px);display:flex;flex-direction:column;justify-content:center}
.ghost{margin:0;position:absolute;left:0;right:0;bottom:3%;font-size:clamp(46px,14vw,84px);
font-weight:900;line-height:1;color:var(--ink);opacity:.012;
white-space:nowrap;overflow:hidden;pointer-events:none;letter-spacing:-.04em}
.prev,.next,.prev2,.next2{margin:0;color:var(--sub);min-height:16px;
white-space:nowrap;overflow:hidden;text-overflow:ellipsis;letter-spacing:.02em}
.prev,.next{font-size:12.5px;opacity:.68}
.prev2,.next2{font-size:11px;opacity:.36}
.prev{margin-bottom:9px}
.next{margin-top:10px}
.prev2{margin-bottom:5px}
.next2{margin-top:6px}
.lyric{margin:0;font-size:clamp(25px,6.4vw,42px);line-height:1.34;font-weight:800;letter-spacing:.01em;
text-wrap:balance;text-shadow:0 6px 26px var(--shadow);transition:color .35s,text-shadow .35s,letter-spacing .35s}
.trans{margin:9px 0 0;font-size:13px;line-height:1.5;color:var(--accent);opacity:.88;min-height:18px}
footer{flex:0 0 auto;padding-top:4px}
.progress{position:relative;height:26px;display:flex;align-items:center;cursor:pointer;touch-action:none}
.track{position:absolute;left:0;right:0;height:4px;border-radius:99px;background:var(--line);overflow:hidden}
.fill{position:absolute;left:0;top:0;bottom:0;width:0;border-radius:99px;background:linear-gradient(90deg,var(--accent),var(--accent2));transition:width .3s linear}
.knob{position:absolute;width:11px;height:11px;border-radius:50%;background:var(--ink);left:0;transform:translateX(-50%);
box-shadow:0 0 0 4px color-mix(in srgb,var(--accent) 24%,transparent),0 2px 8px rgba(0,0,0,.35);transition:left .3s linear}
.times{display:flex;justify-content:space-between;font-size:11px;color:var(--sub);font-variant-numeric:tabular-nums;margin-top:-2px}
.controls{display:flex;align-items:center;justify-content:center;gap:24px;margin:14px 0 4px}
.ctl{width:52px;height:52px;border-radius:50%;border:1px solid var(--line);background:var(--panel);color:var(--ink);
display:grid;place-items:center;padding:0;cursor:pointer;transition:transform .12s,border-color .2s,background .2s}
.ctl:active{transform:scale(.9)}
.ctl svg{width:20px;height:20px;fill:currentColor}
.ctl.play{width:70px;height:70px;border:0;background:linear-gradient(145deg,var(--accent),var(--accent2));color:#04141a;
box-shadow:0 10px 34px color-mix(in srgb,var(--accent) 28%,transparent)}
.ctl.play svg{width:27px;height:27px}
.volume{display:flex;align-items:center;gap:10px;margin:10px 2px 2px;color:var(--sub)}
.volume svg{width:16px;height:16px;fill:currentColor;flex:0 0 auto}
input[type=range]{-webkit-appearance:none;appearance:none;flex:1;height:20px;background:transparent;outline:none}
input[type=range]::-webkit-slider-runnable-track{height:3px;border-radius:99px;background:var(--line)}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:14px;height:14px;border-radius:50%;
background:var(--accent);margin-top:-5.5px;box-shadow:0 0 0 4px color-mix(in srgb,var(--accent) 18%,transparent)}
input[type=range]::-moz-range-track{height:3px;border-radius:99px;background:var(--line)}
input[type=range]::-moz-range-thumb{border:0;width:14px;height:14px;border-radius:50%;background:var(--accent)}
.themes{display:flex;gap:7px;overflow-x:auto;scrollbar-width:none;padding:12px 0 4px;margin:0 -2px;
-webkit-mask-image:linear-gradient(90deg,transparent,#000 12px,#000 calc(100% - 12px),transparent);mask-image:linear-gradient(90deg,transparent,#000 12px,#000 calc(100% - 12px),transparent)}
.themes::-webkit-scrollbar{display:none}
.chip{flex:0 0 auto;border:1px solid var(--line);background:var(--panel);color:var(--sub);border-radius:99px;
padding:7px 11px;font-size:11px;font-weight:700;letter-spacing:.04em;cursor:pointer;display:flex;align-items:center;gap:6px;
transition:color .2s,border-color .2s,background .2s}
.chip i{width:8px;height:8px;border-radius:50%;background:var(--chip)}
.chip.on{color:var(--ink);border-color:var(--accent);background:color-mix(in srgb,var(--accent) 13%,transparent)}
.foot{margin:3px 0 0;text-align:center;font-size:10px;letter-spacing:.08em;color:var(--sub);opacity:.55}
@media (max-height:620px){.meta{margin-bottom:10px}.lyricWrap{min-height:96px}.controls{margin:8px 0 2px}.themes{padding-top:8px}}
@media (max-width:370px){header{gap:7px}.brand span{display:none}.stageName{font-size:9px;padding:3px 7px}.conn span{font-size:10px}}
/* 14 种歌词舞台的网页版皮肤 */
.t0 .lyric{background:linear-gradient(100deg,var(--ink) 8%,var(--accent) 46%,var(--ink) 92%);
-webkit-background-clip:text;background-clip:text;color:transparent;text-shadow:none;filter:drop-shadow(0 6px 24px var(--shadow))}
.t1 .lyric{font-weight:300;letter-spacing:.2em;color:var(--ink)}
.t1 .lyricWrap:before{content:'';position:absolute;left:50%;top:50%;width:min(78vw,360px);aspect-ratio:1;transform:translate(-50%,-50%);
border-radius:50%;border:1px solid var(--line);opacity:.5}
.t2 .lyricWrap{text-align:left;padding-left:18px;border-left:1px solid var(--line)}
.t2 .lyric{font-weight:700}
.t2 .meta{text-align:left}
.t3 .lyric{font-family:Georgia,"Songti SC",serif;font-weight:700;letter-spacing:.02em}
.t4 .lyric{font-family:Georgia,"Songti SC",serif;font-style:italic;font-weight:600;letter-spacing:.01em}
.t5 .lyricWrap{text-align:left;border-left:3px solid var(--accent);padding-left:17px}
.t5 .meta{text-align:left}
.t6 .lyricWrap{transform:rotate(-2deg)}
.t6 .lyric{font-weight:700}
.t7 .lyricWrap:before{content:'';position:absolute;left:50%;top:50%;width:min(92vw,470px);aspect-ratio:1.9;
transform:translate(-50%,-50%);border:1px solid var(--accent);border-radius:50%;opacity:.24}
.t7 .lyric{letter-spacing:.03em}
.t8 .lyric{font-weight:800;letter-spacing:.01em;text-shadow:0 1px 0 var(--line),0 16px 36px var(--shadow)}
.t8 .lyricWrap:after{content:'';position:absolute;left:10%;right:10%;top:50%;height:1px;background:linear-gradient(90deg,transparent,var(--accent),transparent);opacity:.5}
.t9 .lyric{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:clamp(22px,5.6vw,36px);letter-spacing:-.01em}
.t9 .artist,.t9 .times{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
.t10{--ink:#1c1917;--sub:#57534e;--panel:rgba(28,25,23,.05);--line:rgba(28,25,23,.16);--shadow:rgba(28,25,23,.18)}
.t10 .shade{background:radial-gradient(120% 85% at 50% 8%,transparent 0%,rgba(120,100,70,.05) 60%,rgba(120,100,70,.12) 100%)}
.t10 .lyric{font-family:Georgia,"Songti SC",serif;font-weight:600;letter-spacing:.01em}
.t10 .ctl.play{color:#fff8ec}
.t10 .ghost{display:none}
.t11 .lyric{background:linear-gradient(100deg,var(--accent),var(--ink) 44%,var(--accent2));
-webkit-background-clip:text;background-clip:text;color:transparent;text-shadow:none}
.t12 .lyric{text-shadow:0 0 18px color-mix(in srgb,var(--accent) 55%,transparent),0 0 48px color-mix(in srgb,var(--accent2) 32%,transparent)}
.t13 .lyric{font-weight:200;letter-spacing:.07em;color:var(--ink)}
.t13 .ghost{opacity:.022}
.t13 .shade{background:linear-gradient(180deg,rgba(0,0,0,.16),transparent 30%,transparent 72%,rgba(0,0,0,.28))}
.hide{display:none!important}
</style></head><body>
<canvas id="bg"></canvas>
<div class="shade"></div>
<main>
  <header>
    <div class="brand"><b>JUN</b><span>MUSIC · REMOTE</span></div>
    <div class="stageName" id="stageName">流光</div>
    <div class="conn"><i></i><span id="conn">连接中</span></div>
  </header>
  <section class="hero">
    <div class="meta">
      <h1 class="title" id="title">正在连接…</h1>
      <p class="artist" id="artist"></p>
    </div>
    <div class="lyricWrap" id="lyricWrap" data-ghost="">
      <p class="ghost" id="ghost"></p>
      <p class="prev2" id="prev2"></p>
      <p class="prev" id="prev"></p>
      <h2 class="lyric" id="lyric">—</h2>
      <p class="trans" id="trans"></p>
      <p class="next" id="next"></p>
      <p class="next2" id="next2"></p>
    </div>
  </section>
  <footer>
    <div class="progress" id="progress"><div class="track"></div><div class="fill" id="fill"></div><i class="knob" id="knob"></i></div>
    <div class="times"><span id="pos">0:00</span><span id="dur">0:00</span></div>
    <div class="controls">
      <button class="ctl" onclick="cmd('prev')" aria-label="上一首"><svg viewBox="0 0 24 24"><path d="M6 4h2.6v16H6zM20 4.6v14.8c0 .8-.9 1.3-1.6.8L9 12.8a1 1 0 0 1 0-1.6l9.4-7.4c.7-.5 1.6 0 1.6.8z"/></svg></button>
      <button class="ctl play" id="play" onclick="cmd('toggle')" aria-label="播放或暂停"><svg id="playIcon" viewBox="0 0 24 24"><path d="M8 4.7v14.6c0 .9 1 1.4 1.7.9l11-7.3a1.1 1.1 0 0 0 0-1.8l-11-7.3c-.7-.5-1.7 0-1.7.9z"/></svg></button>
      <button class="ctl" onclick="cmd('next')" aria-label="下一首"><svg viewBox="0 0 24 24"><path d="M15.4 4H18v16h-2.6zM4 4.6v14.8c0 .8.9 1.3 1.6.8L15 12.8a1 1 0 0 0 0-1.6L5.6 3.8C4.9 3.3 4 3.8 4 4.6z"/></svg></button>
    </div>
    <div class="volume">
      <svg viewBox="0 0 24 24"><path d="M4 9v6h4l5 4V5L8 9H4zm12.5 3a4.5 4.5 0 0 0-2.3-3.9v7.8a4.5 4.5 0 0 0 2.3-3.9zm-2.3-8.3v2.1a6.8 6.8 0 0 1 0 12.4v2.1a8.9 8.9 0 0 0 0-16.6z"/></svg>
      <input id="vol" type="range" min="0" max="100" value="100" aria-label="音量">
    </div>
    <div class="themes" id="themes"></div>
    <p class="foot">同一 Wi-Fi · 数据不出局域网</p>
  </footer>
</main>
<script>
var THEMES=[
 ['流光','#22d3ee','#7c3aed','#070b12'],
 ['心象','#c4b5fd','#f0abfc','#0d0a16'],
 ['云阶','#7dd3fc','#38bdf8','#081018'],
 ['浮名','#fbbf24','#f59e0b','#0e0c08'],
 ['莫奈','#f9a8d4','#5eead4','#0c0b12'],
 ['群唱','#fb923c','#f472b6','#120c0a'],
 ['倾诉','#f87171','#fbbf24','#120b0b'],
 ['回环','#facc15','#fb7185','#0b0908'],
 ['镜台','#a5b4fc','#22d3ee','#080b14'],
 ['时计','#93c5fd','#fcd34d','#0a0c10'],
 ['商籁','#b45309','#78716c','#f5f2ea'],
 ['凝彩','#e879f9','#38bdf8','#0c0710'],
 ['绘光','#fde68a','#a3e635','#0b0d08'],
 ['静止','#d4d4d8','#71717a','#0a0a0b']
];
var q=new URLSearchParams(location.search),token=q.get('t')||'';
var theme=0,playing=false,dragging=false,last=null;
function api(p,opt){return fetch(p+(p.indexOf('?')>=0?'&':'?')+'t='+encodeURIComponent(token),opt).then(function(r){return r.json()}).catch(function(){return null})}
function fmt(ms){ms=Math.max(0,ms|0);var s=Math.floor(ms/1000);return Math.floor(s/60)+':'+String(s%60).padStart(2,'0')}
function hexA(h,a){var n=parseInt(h.slice(1),16);return 'rgba('+((n>>16)&255)+','+((n>>8)&255)+','+(n&255)+','+a+')'}
function applyTheme(i){
 theme=Math.max(0,Math.min(THEMES.length-1,i|0));
 var t=THEMES[theme],r=document.documentElement.style;
 r.setProperty('--accent',t[1]);r.setProperty('--accent2',t[2]);r.setProperty('--bg',t[3]);
 document.body.className='t'+theme;
 document.querySelector('meta[name=theme-color]').setAttribute('content',t[3]);
 document.getElementById('stageName').textContent=t[0]+' · '+(theme+1)+'/14';
 var chips=document.querySelectorAll('.chip');
 for(var k=0;k<chips.length;k++)chips[k].classList.toggle('on',+chips[k].dataset.i===theme);
 try{localStorage.setItem('jm_remote_theme',String(theme))}catch(e){}
 if(chips[theme]&&chips[theme].scrollIntoView)chips[theme].scrollIntoView({inline:'center',block:'nearest'});
}
function buildThemes(){
 var box=document.getElementById('themes'),html='';
 for(var i=0;i<THEMES.length;i++){
  html+='<button class="chip" data-i="'+i+'" style="--chip:'+THEMES[i][1]+'" onclick="applyTheme('+i+')"><i></i>'+THEMES[i][0]+'</button>';
 }
 box.innerHTML=html;
}
var cv=document.getElementById('bg'),cx=cv.getContext('2d'),W=0,H=0,DPR=1;
function resize(){
 DPR=Math.min(2,window.devicePixelRatio||1);W=innerWidth;H=innerHeight;
 cv.width=Math.floor(W*DPR);cv.height=Math.floor(H*DPR);cv.style.width=W+'px';cv.style.height=H+'px';
 cx.setTransform(DPR,0,0,DPR,0,0);
}
function bgFrame(now){
 var t=now/1000,th=THEMES[theme],a=th[1],b=th[2],ink=(theme===10)?'#1c1917':'#f7f8fb';
 cx.clearRect(0,0,W,H);
 var g=cx.createLinearGradient(0,0,W*.25,H);
 g.addColorStop(0,hexA(th[3],0.2));g.addColorStop(.55,th[3]);g.addColorStop(1,hexA(a,0.07));
 cx.fillStyle=g;cx.fillRect(0,0,W,H);
 if(theme===0){
  var x=W*(.30+.10*Math.sin(t*.35)),y=H*(.30+.06*Math.cos(t*.28));
  var rg=cx.createRadialGradient(x,y,0,x,y,W*.55);
  rg.addColorStop(0,hexA(a,.22));rg.addColorStop(.55,hexA(b,.10));rg.addColorStop(1,hexA(a,0));
  cx.fillStyle=rg;cx.fillRect(0,0,W,H);
  cx.save();cx.translate(W*.5,H*.5);cx.rotate(-.5+Math.sin(t*.18)*.08);
  var lg=cx.createLinearGradient(-W,0,W,0);lg.addColorStop(0,hexA(a,0));lg.addColorStop(.5,hexA(b,.09));lg.addColorStop(1,hexA(a,0));
  cx.fillStyle=lg;cx.fillRect(-W,-H*.028,W*2,H*.056);cx.restore();
 }else if(theme===1){
  cx.font='600 18px Georgia,serif';
  for(var i=0;i<26;i++){
   var px=(i*97+31)%W,py=((i*53+17)%H+Math.sin(t*.25+i)*8);
   cx.fillStyle=hexA(i%3?b:a,.05+.08*((i*7)%10)/10);
   cx.fillText(String.fromCharCode(0x4e00+((i*389+71)%1200)),px,py);
  }
  cx.strokeStyle=hexA(a,.10);cx.beginPath();cx.arc(W*.5,H*.44,Math.min(W,H)*.34,0,6.283);cx.stroke();
 }else if(theme===2){
  for(var i=0;i<9;i++){
   var yy=H*(.16+i*.085),off=Math.sin(t*.5+i*.6)*18;
   cx.fillStyle=hexA(i%2?a:b,.055);
   cx.fillRect(W*.14+off,yy,W*(.72-i*.035),1.5);
  }
 }else if(theme===3){
  cx.font='900 '+Math.floor(W*.16)+'px Georgia,serif';
  for(var i=0;i<5;i++){
   var yy2=H*(.24+i*.14)+Math.sin(t*.3+i)*5;
   cx.fillStyle=hexA(a,.014+.008*i);
   cx.fillText('浮名',-W*.04,yy2);
  }
 }else if(theme===4){
  var cols=[a,b,'#fbbf24','#5eead4'];
  for(var i=0;i<5;i++){
   var xxx=W*(.22+.16*i+.05*Math.sin(t*.22+i)),yyy=H*(.26+.14*((i*3)%4)+.04*Math.cos(t*.19+i));
   var rr=W*(.20+.06*(i%3));
   var rg2=cx.createRadialGradient(xxx,yyy,0,xxx,yyy,rr);
   rg2.addColorStop(0,hexA(cols[i%cols.length],.14));rg2.addColorStop(1,hexA(cols[i%cols.length],0));
   cx.fillStyle=rg2;cx.fillRect(0,0,W,H);
  }
 }else if(theme===5){
  for(var i=0;i<22;i++){
   var hh=(.12+.22*Math.abs(Math.sin(t*.9+i*.7)))*H;
   cx.fillStyle=hexA(i%2?a:b,.16);
   cx.fillRect(W*.5+(i-11)*W*.036,H*.72-hh,W*.018,hh);
  }
 }else if(theme===6){
  cx.save();cx.translate(W*.5,H*.5);cx.rotate(-.06);
  for(var i=0;i<7;i++){
   cx.fillStyle=hexA(i%2?a:b,.09);
   cx.fillRect(-W*.62,H*(.06+i*.11),W*1.24,1.5);
  }
  cx.restore();
 }else if(theme===7){
  cx.save();cx.translate(W*.5,H*.45);cx.rotate(t*.06);
  for(var i=0;i<4;i++){
   cx.strokeStyle=hexA(i?b:a,.10+i*.03);cx.beginPath();
   cx.ellipse(0,0,W*(.24+i*.07),H*(.13+i*.045),0,0,6.283);cx.stroke();
  }
  cx.restore();
 }else if(theme===8){
  var hz={x:W*.5,y:H*.44};
  for(var i=-6;i<=6;i++){
   cx.strokeStyle=hexA(a,.055);cx.beginPath();cx.moveTo(hz.x,hz.y);
   cx.lineTo(W*.5+i*W*.12,H);cx.stroke();
   cx.strokeStyle=hexA(ink,.03);cx.beginPath();cx.moveTo(W*.5+i*W*.12,0);cx.lineTo(W*.5+i*W*.2,H);cx.stroke();
  }
  cx.strokeStyle=hexA(a,.12);cx.beginPath();cx.moveTo(0,hz.y);cx.lineTo(W,hz.y);cx.stroke();
 }else if(theme===9){
  var cc={x:W*.5,y:H*.42},rr2=Math.min(W,H)*.30;
  cx.strokeStyle=hexA(ink,.10);cx.lineWidth=1;cx.beginPath();cx.arc(cc.x,cc.y,rr2,0,6.283);cx.stroke();
  for(var i=0;i<60;i++){
   var ang=i*Math.PI/30,len=i%5?3:7;
   cx.strokeStyle=hexA(ink,i%5?.18:.4);
   cx.beginPath();cx.moveTo(cc.x+Math.cos(ang)*rr2,cc.y+Math.sin(ang)*rr2);
   cx.lineTo(cc.x+Math.cos(ang)*(rr2-len),cc.y+Math.sin(ang)*(rr2-len));cx.stroke();
  }
  cx.strokeStyle=hexA(a,.65);cx.lineWidth=2;cx.beginPath();cx.moveTo(cc.x,cc.y);
  cx.lineTo(cc.x+Math.cos(t*.5-Math.PI/2)*rr2*.55,cc.y+Math.sin(t*.5-Math.PI/2)*rr2*.55);cx.stroke();
  cx.strokeStyle=hexA(ink,.35);cx.lineWidth=1.6;cx.beginPath();cx.moveTo(cc.x,cc.y);
  cx.lineTo(cc.x+Math.cos(t*.06-Math.PI/2)*rr2*.38,cc.y+Math.sin(t*.06-Math.PI/2)*rr2*.38);cx.stroke();
 }else if(theme===10){
  cx.fillStyle=hexA('#8a7455',.05);
  for(var i=0;i<600;i++){
   var xx=(i*73)%W,yy3=(i*151)%H;
   cx.fillRect(xx,yy3,1,1);
  }
 }else if(theme===11){
  var g3=cx.createLinearGradient(0,H,W,0);
  g3.addColorStop(0,hexA(a,.20));g3.addColorStop(.4,hexA(b,.10));g3.addColorStop(.7,hexA('#fbbf24',.10));g3.addColorStop(1,hexA(a,0));
  cx.fillStyle=g3;cx.fillRect(0,0,W,H);
  for(var i=0;i<3;i++){
   var yy4=H*(.3+i*.2)+Math.sin(t*.3+i)*10;
   cx.fillStyle=hexA(i%2?b:a,.05);cx.fillRect(0,yy4,W,2);
  }
 }else if(theme===12){
  for(var i=0;i<4;i++){
   var base=W*(.10+i*.26),sway=Math.sin(t*.6+i*1.3)*10;
   var lg2=cx.createLinearGradient(base,0,base+W*.24,H);
   lg2.addColorStop(0,hexA(a,i===1?.20:.11));lg2.addColorStop(.6,hexA(b,.05));lg2.addColorStop(1,hexA(a,0));
   cx.fillStyle=lg2;cx.beginPath();cx.moveTo(base-30+sway,0);cx.lineTo(base+34+sway,0);
   cx.lineTo(base+W*.26+sway,H);cx.lineTo(base+W*.05+sway,H);cx.closePath();cx.fill();
  }
 }else{
  var rg4=cx.createRadialGradient(W*.5,H*.4,0,W*.5,H*.4,Math.max(W,H)*.62);
  rg4.addColorStop(0,hexA(a,.05));rg4.addColorStop(1,hexA(a,0));cx.fillStyle=rg4;cx.fillRect(0,0,W,H);
 }
 requestAnimationFrame(bgFrame);
}
function setPlaying(p){
 playing=!!p;
 document.getElementById('playIcon').innerHTML=playing
  ?'<path d="M7 4h3.4v16H7zM13.6 4H17v16h-3.4z"/>'
  :'<path d="M8 4.7v14.6c0 .9 1 1.4 1.7.9l11-7.3a1.1 1.1 0 0 0 0-1.8l-11-7.3c-.7-.5-1.7 0-1.7.9z"/>';
}
async function tick(){
 var s=await api('/api/state');
 if(!s){document.getElementById('conn').textContent='已断开';return}
 last=s;
 document.getElementById('conn').textContent=s.playing?'正在播放':'已暂停';
 document.getElementById('title').textContent=s.title||'Jun Music';
 document.getElementById('artist').textContent=(s.artist||'')+(s.queue?('  ·  '+(s.index+1)+'/'+s.queue):'');
 var cur=s.lyric||'';
 document.getElementById('lyric').textContent=cur||'♪';
 document.getElementById('ghost').textContent=cur.slice(0,6);
 document.getElementById('lyricWrap').dataset.ghost=cur.slice(0,8);
 document.getElementById('prev2').textContent=s.prev2||'';
 document.getElementById('prev').textContent=s.prev||'';
 document.getElementById('trans').textContent=s.translation||'';
 document.getElementById('next').textContent=s.next||'';
 document.getElementById('next2').textContent=s.next2||'';
 document.getElementById('lyric').style.letterSpacing='';
 setPlaying(s.playing);
 var pct=s.duration?Math.min(100,Math.max(0,s.position/s.duration*100)):0;
 document.getElementById('fill').style.width=pct+'%';
 document.getElementById('knob').style.left=pct+'%';
 document.getElementById('pos').textContent=fmt(s.position);
 document.getElementById('dur').textContent=fmt(s.duration);
 if(!dragging){document.getElementById('vol').value=Math.round((s.volume||0)*100)}
}
function cmd(c){
 api('/api/'+c,{method:'POST'});setTimeout(tick,130);
}
var seekTimer=0;
document.getElementById('progress').addEventListener('pointerdown',function(e){
 dragging=true;var el=e.currentTarget;
 function move(ev){
  var r=el.getBoundingClientRect(),pct=Math.max(0,Math.min(1,(ev.clientX-r.left)/r.width));
  document.getElementById('fill').style.width=(pct*100)+'%';
  document.getElementById('knob').style.left=(pct*100)+'%';
  if(last&&last.duration)document.getElementById('pos').textContent=fmt(last.duration*pct);
 }
 function up(ev){
  var r=el.getBoundingClientRect(),pct=Math.max(0,Math.min(1,(ev.clientX-r.left)/r.width));
  if(last&&last.duration)api('/api/seek?ms='+Math.round(last.duration*pct),{method:'POST'});
  dragging=false;document.removeEventListener('pointermove',move);document.removeEventListener('pointerup',up);setTimeout(tick,160);
 }
 move(e);document.addEventListener('pointermove',move);document.addEventListener('pointerup',up);
});
var vol=document.getElementById('vol');
vol.addEventListener('pointerdown',function(){dragging=true});
vol.addEventListener('pointerup',function(){dragging=false});
vol.addEventListener('input',function(){
 clearTimeout(seekTimer);
 seekTimer=setTimeout(function(){api('/api/volume?v='+(vol.value/100),{method:'POST'})},120);
});
buildThemes();
var stored=0;try{stored=parseInt(localStorage.getItem('jm_remote_theme')||'0',10)||0}catch(e){}
var qTheme=parseInt(q.get('theme')||q.get('stage')||'',10);if(!isNaN(qTheme))stored=qTheme;
applyTheme(stored);
resize();addEventListener('resize',resize);
requestAnimationFrame(bgFrame);
setInterval(tick,1000);tick();
</script></body></html>
    """.trimIndent()
}

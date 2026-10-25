package th.miracle.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File

/**
 * เว็บ Miracle Project ในรูปแบบแอป Android (WebView)
 * - ทุกอย่างในเว็บทำงานเหมือนเดิม (ล็อกอิน แก้ข้อความ แผนที่ GPS อัปโหลดรูป)
 * - ลิงก์ภายนอก (Google Maps, Instagram, LINE, โทร, SMS) เปิดในแอปที่เกี่ยวข้อง
 * - ไฟล์ที่ดาวน์โหลด (GPX, ICS, CSV, JSON) บันทึกลงโฟลเดอร์ Downloads
 */
class MainActivity : Activity() {

    companion object {
        // เปลี่ยน URL ตรงนี้ถ้าเว็บย้ายที่
        const val HOME = "https://panulakstudio.netlify.app/"
        private const val REQ_PERM = 101
        private const val REQ_FILE = 102

        // สคริปต์ที่ฉีดเข้าหน้าเว็บ: ให้ปุ่มดาวน์โหลดไฟล์ (blob:) ทำงานได้ใน WebView
        private val SHIM = """
(function () {
  if (window.__miracleShim) return;
  window.__miracleShim = true;
  function save(a) {
    var name = a.getAttribute('download') || 'file';
    fetch(a.href).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onload = function () {
        AndroidBridge.saveFile(name, b.type || 'application/octet-stream', String(fr.result).split(',')[1]);
      };
      fr.readAsDataURL(b);
    }).catch(function (err) { console.log('download failed', err); });
  }
  var orig = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && String(this.href).indexOf('blob:') === 0) { save(this); return; }
    return orig.call(this);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && String(a.href).indexOf('blob:') === 0) { e.preventDefault(); save(a); }
  }, true);
})();
""".trimIndent()

        private const val OFFLINE_HTML = """<!doctype html><html lang="th"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;color:#334155;text-align:center;padding:24px}
button{margin-top:16px;padding:12px 24px;border:0;border-radius:10px;background:#0284c7;color:#fff;font-size:16px}</style>
</head><body><h2>ไม่มีการเชื่อมต่ออินเทอร์เน็ต</h2><p>กรุณาตรวจสอบการเชื่อมต่อแล้วลองอีกครั้ง</p>
<button onclick="AndroidBridge.retry()">ลองอีกครั้ง</button></body></html>"""
    }

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var geoOrigin: String? = null
    private var geoCb: GeolocationPermissions.Callback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        bar = findViewById(R.id.bar)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "$userAgentString MiracleApp/1.0"
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.setDownloadListener { url, _, _, _, _ -> openExternal(url) }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                bar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                bar.visibility = View.GONE
                view.evaluateJavascript(SHIM, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(HOME, OFFLINE_HTML, "text/html", "utf-8", null)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
            }

            // ขอสิทธิ์ตำแหน่ง (GPS) ตอนที่เว็บเรียกใช้
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCb = callback
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_PERM
                    )
                }
            }

            // เว็บไม่ได้ใช้กล้องหรือไมค์ จึงปฏิเสธทั้งหมด
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            // ปุ่มอัปโหลดรูปในเว็บ เปิดตัวเลือกไฟล์ของเครื่อง
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                return try {
                    val intent = params.createIntent()
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }

            // window.open() จากในเว็บ ให้เปิดในเบราว์เซอร์/แอปภายนอก
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        openExternal(request.url.toString())
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            geoCb?.invoke(geoOrigin ?: "", ok, false)
            geoCb = null
            geoOrigin = null
            if (!ok) toast("ไม่ได้รับสิทธิ์ตำแหน่ง ฟีเจอร์ GPS จะใช้ไม่ได้")
        }
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ลิงก์ของเว็บเองเปิดในแอป ลิงก์อื่นเปิดนอกแอป
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme == "http" || scheme == "https") {
            if (uri.host == Uri.parse(HOME).host) return false
            openExternal(uri.toString())
            return true
        }
        if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return false
        // tel:, sms:, mailto:, geo:, intent:, line:// ฯลฯ
        openExternal(uri.toString())
        return true
    }

    private fun openExternal(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                         else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            toast("เปิดลิงก์นี้ไม่ได้ อาจต้องติดตั้งแอปที่เกี่ยวข้องก่อน")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // สะพานให้หน้าเว็บเรียกโค้ด Android
    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("สร้างไฟล์ไม่ได้")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    File(dir, safe).writeBytes(bytes)
                }
                toast("บันทึกไฟล์ $safe แล้ว (โฟลเดอร์ดาวน์โหลด)")
            } catch (e: Exception) {
                toast("บันทึกไม่สำเร็จ: ${e.message}")
            }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread { web.loadUrl(HOME) }
        }
    }
}

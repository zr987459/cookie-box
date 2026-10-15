package com.cookiebox.app.engine

import android.webkit.WebView
import java.net.URLEncoder

object WebTranslator {

    /**
     * 方式一：向网页注入 Google 网页内即时翻译引擎 (原地翻译，免刷新，保留原有会话)
     */
    fun translateInPage(webView: WebView, targetLanguage: String = "zh-CN") {
        val js = """
            (function() {
                if (window._googleTranslateInitDone) {
                    var select = document.querySelector('.goog-te-combo');
                    if (select) {
                        select.value = '$targetLanguage';
                        select.dispatchEvent(new Event('change'));
                    }
                    return;
                }
                
                var div = document.getElementById('google_translate_element');
                if (!div) {
                    div = document.createElement('div');
                    div.id = 'google_translate_element';
                    div.style.display = 'none';
                    document.body.appendChild(div);
                }

                window._googleTranslateInit = function() {
                    new google.translate.TranslateElement({
                        pageLanguage: 'auto',
                        includedLanguages: '$targetLanguage,en,ja,ko,ru,fr,de,es',
                        autoDisplay: false
                    }, 'google_translate_element');

                    var checkCount = 0;
                    var timer = setInterval(function() {
                        var select = document.querySelector('.goog-te-combo');
                        if (select) {
                            select.value = '$targetLanguage';
                            select.dispatchEvent(new Event('change'));
                            clearInterval(timer);
                            window._googleTranslateInitDone = true;
                        }
                        if (++checkCount > 20) clearInterval(timer);
                    }, 300);
                };

                var script = document.createElement('script');
                script.id = 'google_translate_script';
                script.src = 'https://translate.google.com/translate_a/element.js?cb=_googleTranslateInit';
                document.body.appendChild(script);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * 方式二：生成 Google Translate 网页翻译代理阅读地址 (带 CSP 防御或网络异常时的兜底通道)
     */
    fun getTranslateProxyUrl(url: String, targetLanguage: String = "zh-CN"): String {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            "https://translate.google.com/translate?sl=auto&tl=$targetLanguage&u=$encoded"
        } catch (e: Exception) {
            url
        }
    }
}

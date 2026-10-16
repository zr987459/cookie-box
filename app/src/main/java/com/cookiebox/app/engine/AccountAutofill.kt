package com.cookiebox.app.engine

import org.json.JSONObject

/**
 * 账号填入相关脚本。为保持「零 DOM 污染」的反检测策略：
 *  - 不注册 addJavascriptInterface（网页可探测到全局对象）
 *  - 不在 window 上留变量、不挂常驻监听器
 *  - 只在用户主动点「填入 / 保存」时才改动或读取表单
 */
object AccountAutofill {

    /** 只读检测：页面上有没有可见的密码框。不改页面、不留任何全局变量 */
    const val DETECT_JS =
        "(function(){var p=document.querySelectorAll('input[type=password]');for(var i=0;i<p.length;i++){if(p[i].offsetWidth||p[i].offsetHeight)return true;}return false;})()"

    /** 只读：读取用户在当前页登录框里已经输入的账号和密码，返回 [账号, 密码] */
    val READ_JS = """
(function(){
  function visible(el){ return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length)); }
  var pw = Array.prototype.filter.call(document.querySelectorAll('input[type=password]'), function(p){ return p.value; })[0] || null;
  var scope = (pw && pw.form) || document;
  var cands = Array.prototype.filter.call(scope.querySelectorAll('input'), function(i){
    return /^(text|email|tel|)$/i.test(i.type || '') && visible(i) && i.value;
  });
  var u = '';
  if (pw) cands.forEach(function(i){ if (i.compareDocumentPosition(pw) & 4) u = i.value; });
  if (!u && cands.length) u = cands[0].value;
  return [u, pw ? pw.value : ''];
})()
""".trimIndent()

    /** 把账号密码填进当前页面的登录表单，兼容 React / Vue 等受控输入框 */
    fun fillJs(username: String, password: String): String = """
(function(u, p){
  function visible(el){ return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length)); }
  function set(el, v){
    try {
      var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      el.focus();
      d.set.call(el, v);
      el.dispatchEvent(new Event('input', {bubbles: true}));
      el.dispatchEvent(new Event('change', {bubbles: true}));
      el.blur();
    } catch(e) { el.value = v; }
  }
  var pw = Array.prototype.filter.call(document.querySelectorAll('input[type=password]'), visible)[0] || null;
  var scope = (pw && pw.form) || document;
  var cands = Array.prototype.filter.call(scope.querySelectorAll('input'), function(i){
    return /^(text|email|tel|)$/i.test(i.type || '') && visible(i) && !i.readOnly && !i.disabled;
  });
  var user = null;
  if (pw) cands.forEach(function(i){ if (i.compareDocumentPosition(pw) & 4) user = i; });
  if (!user) user = cands.filter(function(i){ return /user|mail|login|account|phone|mobile|name/i.test((i.name||'')+(i.id||'')+(i.autocomplete||'')+(i.placeholder||'')); })[0] || cands[0] || null;
  if (user && u) set(user, u);
  if (pw && p) set(pw, p);
  return (user && u ? 1 : 0) + (pw && p ? 2 : 0);
})(${JSONObject.quote(username)}, ${JSONObject.quote(password)});
""".trimIndent()
}

// Replace NodeBB's two icon rails with ONE top bar that mirrors the website nav.
// Stored in config.customJS (served raw, runs on every page). ASCII-only.
// PROD copy: __SITE_URL__ and __CID_GUIDES__ substituted by restore-forum.sh.
var SITE = "__SITE_URL__";
if (/^__/.test(SITE)) SITE = "http://localhost:3000";

var JS = [
"(function(){",
"  var SITE='" + SITE + "';",
"  var GUIDES='/category/__CID_GUIDES__';",
"  var NAV=[['Home',SITE+'/'],['News',SITE+'/news'],['Hiscores',SITE+'/hiscores'],['Forum','/'],['Guides',GUIDES],['Store',SITE+'/store'],['Vote',SITE+'/vote'],['Play',SITE+'/play']];",
"  function path(){return location.pathname;}",
"  function build(){",
"    var old=document.getElementById('kol-topbar'); if(old) old.remove();",
"    var u=(window.app&&window.app.user)||{};",
"    var p=path();",
"    var left='<a class=\"kol-brand\" href=\"'+SITE+'/\">Fall of <span class=\"kol-gold\">Varrock</span></a><nav class=\"kol-nav\">';",
"    for(var i=0;i<NAV.length;i++){var on=(NAV[i][0]==='Forum'&&(p==='/'||p.indexOf('/category')===0||p.indexOf('/topic')===0))||(NAV[i][0]==='Guides'&&p.indexOf(GUIDES)===0);left+='<a'+(on?' class=\"on\"':'')+' href=\"'+NAV[i][1]+'\">'+NAV[i][0]+'</a>';}",
"    left+='</nav>';",
"    var right='<a class=\"kol-ico\" href=\"/search\" title=\"Search\"><i class=\"fa fa-magnifying-glass\"></i></a>';",
"    right+='<a class=\"kol-ico\" href=\"/notifications\" title=\"Notifications\"><i class=\"fa fa-bell\"></i></a>';",
"    if(u.uid&&u.uid>0){",
"      var av=u.picture?('<img class=\"kol-av\" src=\"'+u.picture+'\"/>'):('<span class=\"kol-av kol-avtxt\" style=\"background:'+(u['icon:bgColor']||'#dc2626')+'\">'+(u.username||'?').charAt(0).toUpperCase()+'</span>');",
"      right+='<a class=\"kol-user\" href=\"/user/'+(u.userslug||'')+'\">'+av+'<span class=\"kol-uname\">'+(u.username||'')+'</span></a>';",
"    } else {",
"      right+='<a class=\"kol-login\" href=\"'+SITE+'/login\">Log in</a>';",
"    }",
"    var bar=document.createElement('div'); bar.id='kol-topbar';",
"    bar.innerHTML='<div class=\"kol-left\">'+left+'</div><div class=\"kol-right\">'+right+'</div>';",
"    document.body.insertBefore(bar,document.body.firstChild);",
"  }",
"  function styleOnce(){",
"    if(document.getElementById('kol-topbar-style'))return;",
"    var css='body{padding-top:0!important}'",
"    +'#kol-topbar{position:sticky;top:0;z-index:10000;display:flex;align-items:center;justify-content:space-between;gap:16px;height:54px;padding:0 20px;background:rgba(11,7,8,.92);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border-bottom:1px solid rgba(255,255,255,.08);font-family:Inter,system-ui,sans-serif}'",
"    +'#kol-topbar .kol-left{display:flex;align-items:center;gap:16px;min-width:0;flex:1}'",
"    +'#kol-topbar .kol-brand{color:#fff;font-weight:700;text-decoration:none;font-size:15px;white-space:nowrap;letter-spacing:.4px;text-transform:uppercase}'",
"    +'#kol-topbar .kol-gold{background:linear-gradient(90deg,#ef4444,#f59e0b);-webkit-background-clip:text;background-clip:text;color:transparent}'",
"    +'#kol-topbar .kol-nav{display:flex;flex-wrap:wrap;gap:2px}'",
"    +'#kol-topbar .kol-nav a{color:rgba(232,233,243,.74);text-decoration:none;font-size:13px;font-weight:500;padding:7px 11px;border-radius:8px;transition:all .15s}'",
"    +'#kol-topbar .kol-nav a:hover{background:rgba(255,255,255,.07);color:#fff}'",
"    +'#kol-topbar .kol-nav a.on{color:#ef4444;background:rgba(220,38,38,.14)}'",
"    +'#kol-topbar .kol-right{display:flex;align-items:center;gap:4px}'",
"    +'#kol-topbar .kol-ico{color:rgba(232,233,243,.78);text-decoration:none;width:36px;height:36px;display:grid;place-items:center;border-radius:9px;font-size:15px}'",
"    +'#kol-topbar .kol-ico:hover{background:rgba(255,255,255,.08);color:#fff}'",
"    +'#kol-topbar .kol-user{display:flex;align-items:center;gap:8px;color:#fff;text-decoration:none;padding:4px 12px 4px 4px;border-radius:999px;margin-left:4px}'",
"    +'#kol-topbar .kol-user:hover{background:rgba(255,255,255,.08)}'",
"    +'#kol-topbar .kol-av{width:28px;height:28px;border-radius:50%;object-fit:cover;display:grid;place-items:center}'",
"    +'#kol-topbar .kol-avtxt{color:#fff;font-weight:700;font-size:13px}'",
"    +'#kol-topbar .kol-uname{font-size:13px;font-weight:600}'",
"    +'#kol-topbar .kol-login{color:#fff;background:linear-gradient(120deg,#ef4444,#b91c1c);text-decoration:none;font-weight:600;font-size:13px;padding:7px 16px;border-radius:8px}'",
"    +'@media(max-width:640px){#kol-topbar .kol-uname{display:none}#kol-topbar{padding:0 12px}}';",
"    var st=document.createElement('style'); st.id='kol-topbar-style'; st.textContent=css; document.head.appendChild(st);",
"  }",
"  styleOnce(); build();",
"  document.addEventListener('DOMContentLoaded',function(){styleOnce();build();});",
"  if(window.$){window.$(window).on('action:ajaxify.end',function(){build();});}",
"})();"
].join("\n");

db.objects.updateOne({ _key: "config" }, { $set: { customJS: JS, useCustomJS: 1 } });
print("customJS top bar written (" + JS.length + " chars)");

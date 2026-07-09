// Deterministically theme NodeBB to match the "Fall of Varrock" website
// (Fallen Crimson & Ash). Writes the full stylesheet into config.customHTML
// (injected raw into <head>, no SCSS compile), clears any Bootswatch skin, disables
// the flaky customCSS field. Run against the `nodebb` DB, then restart NodeBB.
//
// Section icons are served SAME-ORIGIN from NodeBB (/assets/uploads/fov/*.png) so they
// never depend on the web app being up / cross-origin CSP. In dev they're copied in via
// `docker cp`; in production the docker-compose bind-mount keeps them in place. (The
// top-bar nav + widget LINKS still use the env-driven web URL - see nodebb-topbar.js.)
var CSS = [
':root,[data-bs-theme="light"],[data-bs-theme="dark"]{',
'--bs-body-bg:#0b0708;--bs-body-bg-rgb:11,7,8;--bs-body-color:#e8e2d9;--bs-body-color-rgb:232,226,217;',
'--bs-secondary-bg:#140d0e;--bs-secondary-bg-rgb:20,13,14;--bs-tertiary-bg:#1e1416;--bs-tertiary-bg-rgb:30,20,22;',
'--bs-emphasis-color:#fff;--bs-secondary-color:#a39a90;--bs-border-color:rgba(255,255,255,.10);',
'--bs-link-color:#ef4444;--bs-link-color-rgb:239,68,68;--bs-link-hover-color:#f87171;--bs-primary:#dc2626;--bs-primary-rgb:220,38,38;',
'--bs-dropdown-bg:#160d0e;--bs-dropdown-border-color:rgba(255,255,255,.10);--bs-dropdown-link-color:#e8e2d9;--bs-dropdown-link-hover-bg:rgba(255,255,255,.06);--bs-dropdown-link-hover-color:#fff;--bs-dropdown-link-active-bg:rgba(220,38,38,.16);--bs-dropdown-link-active-color:#ef4444;--bs-dropdown-header-color:#a39a90;',
'--bs-offcanvas-bg:#0b0708;--bs-offcanvas-color:#e8e2d9;--bs-popover-bg:#160d0e;--bs-popover-header-bg:#1e1416;--bs-popover-body-color:#e8e2d9;--bs-modal-bg:#140d0e;--bs-modal-color:#e8e2d9;--bs-card-bg:transparent;--bs-list-group-bg:transparent;',
'}',
'body{background-color:#080506!important}a{color:#ef4444}a:hover{color:#f87171}',
'.card,.panel,.alert{background-color:rgba(255,255,255,.035)!important;border-color:rgba(255,255,255,.10)!important}',
'.btn-primary{background:linear-gradient(120deg,#ef4444,#b91c1c)!important;border:0!important;color:#fff!important}',
'.navbar,header[component="navbar"]{background-color:rgba(11,7,8,.85)!important;border-bottom:1px solid rgba(255,255,255,.08)!important}',
'nav.sidebar[component="sidebar/left"],nav.sidebar[component="sidebar/right"],nav.sidebar.bg-light,html body .sidebar.bg-light{background-color:#0b0708!important;border-color:rgba(255,255,255,.08)!important}',
'[component="sidebar/left"],[component="sidebar/right"]{display:none!important}',
'.sidebar .text-dark,.sidebar a,.sidebar .nav-link,.sidebar i{color:#cdbfb8!important}',
'.dropdown-menu,.search-dropdown,.notifications-dropdown,.user-dropdown,[component="header/usercontrol"],.offcanvas,.popover,.modal-content{background-color:#160d0e!important;color:#e8e2d9!important;border-color:rgba(255,255,255,.10)!important}',
'.offcanvas,.offcanvas-body,.offcanvas-header{background-color:#0b0708!important}',
'.dropdown-item{color:#e8e2d9!important}.dropdown-item:hover,.dropdown-item:focus{background-color:rgba(255,255,255,.06)!important;color:#fff!important}',
'.bg-light,.bg-white,.bg-body,.bg-body-tertiary,.bg-body-secondary{background-color:#140d0e!important}',
'.text-dark{color:#e8e2d9!important}',
'.btn-light,.btn-ghost.active,.btn-ghost:active,.btn-outline-light{--bs-btn-bg:#1e1416!important;--bs-btn-color:#e8e2d9!important;--bs-btn-border-color:rgba(255,255,255,.12)!important;--bs-btn-hover-bg:#2a1c1e!important;--bs-btn-hover-color:#fff!important;background-color:#1e1416!important;color:#e8e2d9!important;border-color:rgba(255,255,255,.12)!important}',
'.btn-light:hover,.btn-ghost.active:hover,.btn-ghost:hover{background-color:#2a1c1e!important;color:#fff!important}',
'.placeholder{background-color:#33232a!important;opacity:1!important}',
'.list-group-item{background-color:transparent!important;color:#e8e2d9!important;border-color:rgba(255,255,255,.08)!important}',
'.form-control,.form-select{background-color:rgba(255,255,255,.05)!important;border-color:rgba(255,255,255,.12)!important;color:#e8e2d9!important}',
/* Custom section icons: swap the FontAwesome square for the Fall of Varrock art (hosted on the web app). */
'li.category-6 span.icon,li.category-11 span.icon,li.category-17 span.icon,li.category-21 span.icon,li.category-25 span.icon{background-color:transparent!important;background-size:contain!important;background-repeat:no-repeat!important;background-position:center!important;width:2.75rem!important;height:2.75rem!important}',
'li.category-6 span.icon>i,li.category-11 span.icon>i,li.category-17 span.icon>i,li.category-21 span.icon>i,li.category-25 span.icon>i{display:none!important}',
'li.category-6 span.icon{background-image:url(/assets/uploads/fov/official.png)!important}',
'li.category-11 span.icon{background-image:url(/assets/uploads/fov/general.png)!important}',
'li.category-17 span.icon{background-image:url(/assets/uploads/fov/war.png)!important}',
'li.category-21 span.icon{background-image:url(/assets/uploads/fov/media.png)!important}',
'li.category-25 span.icon{background-image:url(/assets/uploads/fov/support.png)!important}'
].join('');

var header = '<style id="kol-theme">' + CSS + '</style>';
db.objects.updateOne({ _key: "config" }, { $set: {
  customHTML: header,
  useCustomHTML: 1,
  customCSS: "",
  useCustomCSS: 0,
  bootswatchSkin: ""
}});
print("theme written into customHTML (" + header.length + " chars), skin cleared, customCSS disabled");

// Global Sidebar widgets. ASCII-only (FontAwesome icons, no emoji) so the
// PowerShell -> mongosh pipe doesn't mangle UTF-8. Run vs nodebb DB, restart NodeBB.
var SITE = "__SITE_URL__";
if (/^__/.test(SITE)) SITE = "http://localhost:3000";
var gettingStarted =
  '<div class="mb-2 fw-semibold"><i class="fa fa-flag-checkered text-warning me-1"></i> New to the realm?</div>' +
  '<ul class="ps-3 mb-3 small">' +
  '<li><a href="/category/13"><i class="fa fa-book me-1"></i> Browse all Guides</a></li>' +
  '<li><a href="/category/13"><i class="fa fa-scroll me-1"></i> The Recruit Trials</a></li>' +
  '<li><a href="/category/13"><i class="fa fa-khanda me-1"></i> The War Explained</a></li>' +
  '<li><a href="/category/13"><i class="fa fa-shield-halved me-1"></i> Feudal Titles &amp; Ranks</a></li>' +
  '</ul>' +
  '<a class="btn btn-primary btn-sm w-100 mb-1" href="' + SITE + '/play"><i class="fa fa-play me-1"></i> Play Now</a>' +
  '<a class="btn btn-sm w-100" style="border:1px solid rgba(255,255,255,.15)" href="' + SITE + '/store"><i class="fa fa-gem me-1"></i> Donate</a>';

var widgets = [
  { widget: "html", data: { title: "Getting Started", container: "", html: gettingStarted } },
  { widget: "recenttopics", data: { title: "Announcements", container: "", numTopics: "3", cid: "7", relativeTime: "1" } },
  { widget: "recenttopics", data: { title: "Recent Topics", container: "", numTopics: "6", relativeTime: "1" } },
];

db.objects.updateOne({ _key: "widgets:global" }, { $set: { sidebar: JSON.stringify(widgets) } }, { upsert: true });
print("global/sidebar widgets set: " + widgets.length);

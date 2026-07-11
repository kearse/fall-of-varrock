// Rebrand NodeBB globally (runs against the `nodebb` DB on the shared Mongo).
// PROD copy: title:url points at the production site.
var c = db.objects.findOne({ _key: "config" });
print("config exists: " + (!!c) + " | title before: " + (c ? c.title : "n/a"));
db.objects.updateOne(
  { _key: "config" },
  {
    $set: {
      title: "Fall of Varrock",
      browserTitle: "Fall of Varrock",
      "title:url": "__SITE_URL__",
      showSiteTitle: 1,
    },
  }
);
print("updated title -> Fall of Varrock");

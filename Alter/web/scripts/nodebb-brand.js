// Rebrand NodeBB globally (runs against the `nodebb` DB on the shared Mongo).
var c = db.objects.findOne({ _key: "config" });
print("config exists: " + (!!c) + " | title before: " + (c ? c.title : "n/a"));
db.objects.updateOne(
  { _key: "config" },
  {
    $set: {
      title: "Fall of Varrock",
      browserTitle: "Fall of Varrock",
      "title:url": "http://localhost:3000",
      showSiteTitle: 1,
    },
  }
);
print("updated title -> Fall of Varrock");

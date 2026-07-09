var d = db.objects.findOne({ _key: "widgets:global" });
print("keys: " + Object.keys(d).join(", "));
Object.keys(d).forEach(function (k) {
  if (k === "_key") return;
  var v = d[k];
  print("--- area: " + k + " (" + (typeof v) + ") ---");
  print(typeof v === "string" ? v.slice(0, 600) : JSON.stringify(v).slice(0, 600));
});

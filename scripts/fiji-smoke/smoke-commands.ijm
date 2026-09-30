// Both menu commands must be registered by the plugin jar's plugins.config.
List.setCommands;
ok = true;
single = List.get("Object Colocalization Suite");
batch = List.get("Batch (folder)");
if (single != "Object_Colocalization_Suite") {
    print("SMOKE FAIL commands: 'Object Colocalization Suite' -> '" + single + "'");
    ok = false;
}
if (batch != "OCS_Batch") {
    print("SMOKE FAIL commands: 'Batch (folder)' -> '" + batch + "'");
    ok = false;
}
if (ok) print("SMOKE PASS commands");

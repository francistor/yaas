// Populate the data for the basic Yaas testing

var nUsers = 1000;

// Connect
conn = new Mongo();
db = conn.getDB("CLIENTS");

// Delete current data
db.clients.drop();

// Populate
var i;
var status;
var document;
var addon;
for(i = 0; i < nUsers; i++){
    if(i % 11 == 0) status = 2; else status = 0;
    if(i % 4 == 0) addon = "addon_1"; else addon = null;
    client = {
        nasIPAddress: "1.1.1.1",
        nasPort: 1,
        userName: "user_" +  i + "@clientdb.accept",
        password: "password!_" + i,
        legacyClientId: "legacy_" + i,
        addonService: addon
    }
    db.clients.insertOne(client);
}

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <esp_system.h>
#include <ArduinoJson.h>

#include "emqx_ca.h"
#include "secrets.h"

#define RFID_BUTTON_PIN 4
#define GATE_LED_PIN 2

bool previousButtonState = HIGH;
unsigned long lastButtonPress = 0;
const unsigned long DEBOUNCE_MS = 300;

WiFiClientSecure secureClient;
PubSubClient mqtt(secureClient);

// Gate command correlation state.
// Monitor-only: GPIO2 OPEN actuation remains disabled.
String pendingEventId = "";
bool awaitingAuthorization = false;

// Track the transaction authorized by the backend.
// Used to validate subsequent gate-closing commands.
String authorizedEventId = "";
int64_t authorizedTransactionId = 0;
bool passageAuthorized = false;

// One-shot test mode: maximum one RFID publish per ESP32 boot.
bool rfidPublishUsed = false;

// Set to true only for an intentional payment test.
const bool ENABLE_RFID_PAYMENT_TEST = false;

unsigned long pendingEventStartedAt = 0;
const unsigned long AUTH_TIMEOUT_MS = 30000;


void onMqttMessage(char* topic, byte* payload, unsigned int length) {

  const char* expectedTopic =
      "smarttoll/lanes/2/gate-command";

  if (strcmp(topic, expectedTopic) != 0) {
    Serial.println("Ignored: unexpected MQTT topic");
    return;
  }

  JsonDocument doc;

  DeserializationError error =
      deserializeJson(doc, payload, length);

  if (error) {
    Serial.println("Rejected: invalid JSON");
    return;
  }

  if (!doc["eventId"].is<const char*>() ||
      !doc["transactionId"].is<int64_t>() ||
      !doc["status"].is<const char*>() ||
      !doc["command"].is<const char*>()) {
    Serial.println("Rejected: missing or invalid fields");
    return;
  }

  String eventId = doc["eventId"].as<String>();
  int64_t transactionId = doc["transactionId"].as<int64_t>();
  String status = doc["status"].as<String>();
  String command = doc["command"].as<String>();

  if (eventId.length() != 36 || transactionId <= 0) {
    Serial.println("Rejected: invalid event or transaction ID");
    return;
  }

  Serial.print("Gate command received: ");
  Serial.println(command);

  if (command == "OPEN" && status == "AUTHORIZED") {

  if (!awaitingAuthorization) {
    Serial.println("Rejected OPEN: no pending RFID scan");
    return;
  }

  if (eventId != pendingEventId) {
    Serial.println("Rejected OPEN: event ID mismatch");
    return;
  }

  if (millis() - pendingEventStartedAt > AUTH_TIMEOUT_MS) {
    awaitingAuthorization = false;
    pendingEventId = "";

    Serial.println("Rejected OPEN: authorization timeout");
    return;
  }

  // Store the successfully authorized transaction.
  authorizedEventId = eventId;
  authorizedTransactionId = transactionId;
  passageAuthorized = true;

  // Consume the pending event to prevent duplicate OPEN commands.
  awaitingAuthorization = false;
  pendingEventId = "";

  Serial.println("OPEN matched pending RFID event");

  Serial.print("Authorized transaction ID: ");
  Serial.println((long long)authorizedTransactionId);

  Serial.println("Validated OPEN command - actuation disabled");

} else if (command == "CLOSED" &&
           status == "PASSAGE_COMPLETE") {

  if (!passageAuthorized) {
    Serial.println("Rejected CLOSED: no authorized passage");
    return;
  }

  if (eventId != authorizedEventId ||
      transactionId != authorizedTransactionId) {
    Serial.println("Rejected CLOSED: transaction mismatch");
    return;
  }

  // Valid completion for the authorized passage.
  digitalWrite(GATE_LED_PIN, LOW);

  // Consume authorization to reject duplicate CLOSED commands.
  passageAuthorized = false;
  authorizedEventId = "";
  authorizedTransactionId = 0;

  Serial.println("Gate LED: CLOSED");
  Serial.println("Passage completed and authorization cleared");

} else {

  Serial.println("Rejected: invalid command/status combination");
}


}

String generateEventId() {
  uint8_t bytes[16];
  esp_fill_random(bytes, sizeof(bytes));

  // UUID version 4
  bytes[6] = (bytes[6] & 0x0F) | 0x40;
  bytes[8] = (bytes[8] & 0x3F) | 0x80;

  char uuid[37];

  snprintf(
    uuid,
    sizeof(uuid),
    "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
    bytes[0], bytes[1], bytes[2], bytes[3],
    bytes[4], bytes[5],
    bytes[6], bytes[7],
    bytes[8], bytes[9],
    bytes[10], bytes[11], bytes[12],
    bytes[13], bytes[14], bytes[15]
  );

  return String(uuid);
}

void setup() {
  Serial.begin(115200);

  pinMode(RFID_BUTTON_PIN, INPUT_PULLUP);
  pinMode(GATE_LED_PIN, OUTPUT);
  digitalWrite(GATE_LED_PIN, LOW);

Serial.println("Gate LED initialized: CLOSED");
  delay(1000);

  Serial.println("SmartToll ESP32 - MQTT TLS Test");

  WiFi.begin("Wokwi-GUEST", "", 6);

  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("\nWiFi connected!");

  // TLS verification requires a valid clock.
  configTime(0, 0, "pool.ntp.org");

  Serial.print("Synchronizing time");
  time_t now = time(nullptr);

  while (now < 1700000000) {
    delay(500);
    Serial.print(".");
    now = time(nullptr);
  }

  Serial.println("\nTime synchronized");

  secureClient.setCACert(EMQX_ROOT_CA);

  mqtt.setServer(MQTT_HOST, MQTT_PORT);

  mqtt.setCallback(onMqttMessage);

  Serial.println("Connecting to EMQX Cloud...");

  String clientId = "smarttoll-esp32-" +
                    String((uint32_t)ESP.getEfuseMac(), HEX);

  if (mqtt.connect(
      clientId.c_str(),
      MQTT_USERNAME,
      MQTT_PASSWORD)) {

  Serial.println("MQTT CONNECTED!");

  const char* gateTopic =
      "smarttoll/lanes/2/gate-command";

  if (mqtt.subscribe(gateTopic)) {
    Serial.println("Gate-command SUBSCRIBE request sent");
  } else {
    Serial.println("Gate-command SUBSCRIBE request failed");
  }

} else {
  Serial.print("MQTT connection failed. State: ");
  Serial.println(mqtt.state());
}
}

void loop() {
  static unsigned long lastReconnectAttempt = 0;
  static unsigned long lastCheck = 0;

  if (!mqtt.connected()) {
    unsigned long now = millis();

    if (now - lastReconnectAttempt >= 5000) {
      lastReconnectAttempt = now;

      Serial.println("MQTT disconnected. Reconnecting...");

      String clientId =
          "smarttoll-esp32-" +
          String((uint32_t)ESP.getEfuseMac(), HEX);

      if (mqtt.connect(
              clientId.c_str(),
              MQTT_USERNAME,
              MQTT_PASSWORD)) {

        Serial.println("MQTT RECONNECTED!");

        if (mqtt.subscribe(
                "smarttoll/lanes/2/gate-command")) {
          Serial.println("Gate-command subscription requested");
        } else {
          Serial.println("Gate-command subscription request failed");
        }

      } else {
        Serial.print("Reconnect failed. MQTT state: ");
        Serial.println(mqtt.state());
      }
    }
  } else {
    mqtt.loop();
  }

  if (millis() - lastCheck >= 5000) {
    lastCheck = millis();

    Serial.print("MQTT connected: ");
    Serial.println(mqtt.connected() ? "YES" : "NO");

    Serial.print("MQTT state: ");
    Serial.println(mqtt.state());
  }

  delay(10);

// Automatically expire pending RFID authorization.
if (awaitingAuthorization && millis() - pendingEventStartedAt > AUTH_TIMEOUT_MS) {

  awaitingAuthorization = false;
  pendingEventId = "";

  Serial.println("Pending RFID authorization expired");
}

bool currentButtonState = digitalRead(RFID_BUTTON_PIN);

if (previousButtonState == HIGH &&
    currentButtonState == LOW &&
    millis() - lastButtonPress >= DEBOUNCE_MS) {

  lastButtonPress = millis();

  if (!ENABLE_RFID_PAYMENT_TEST) {
    Serial.println("RFID scan blocked: payment test disabled");
    previousButtonState = currentButtonState;
    return;
  }

  if (rfidPublishUsed) {
    Serial.println("RFID scan ignored: one-shot already used");

    // Update button state before leaving loop().
    previousButtonState = currentButtonState;
    return;
  }

  if (awaitingAuthorization) {
    Serial.println("RFID scan ignored: authorization already pending");

  } else if (!mqtt.connected()) {
    Serial.println("RFID scan ignored: MQTT disconnected");

  } else {

  Serial.println("RFID Scan button pressed!");

  Serial.println("RFID Tag: RFID-XYZ-456");
  Serial.println("Vehicle Plate: XYZ-456");
  Serial.println("Lane: 2");

  String eventId = generateEventId();

  pendingEventId = eventId;
  awaitingAuthorization = true;
  pendingEventStartedAt = millis();

  Serial.print("Pending RFID event: ");
  Serial.println(pendingEventId);

String payload =
    "{\"eventId\":\"" + eventId +
    "\",\"rfidTag\":\"RFID-XYZ-456\"" +
    ",\"observedPlate\":\"XYZ-456\"" +
    ",\"laneId\":2}";

Serial.println("Vehicle detection JSON:");
Serial.println(payload);

// ONE-SHOT TEST MODE
const char* detectionTopic =
    "smarttoll/lanes/2/vehicle-detected";

if (rfidPublishUsed) {
  Serial.println("RFID publish blocked: one-shot already used");
} else if (!mqtt.connected()) {

  Serial.println("RFID publish blocked: MQTT disconnected");

} else {

  // Consume the one-shot allowance before attempting publication.
  rfidPublishUsed = true;

  bool published = mqtt.publish(
      detectionTopic,
      payload.c_str(),
      false
  );

  if (published) {
    Serial.println("ONE-SHOT RFID publish queued");
  } else {
    Serial.println("ONE-SHOT RFID publish failed");
  }
}

}

}

previousButtonState = currentButtonState;
}
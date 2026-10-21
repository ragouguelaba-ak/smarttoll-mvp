import getpass
import os
import ssl
import threading

import paho.mqtt.client as mqtt

CLOUD_HOST = "v57f1141.ala.asia-southeast1.emqxsl.com"
CLOUD_PORT = 8883

LOCAL_HOST = "127.0.0.1"
LOCAL_PORT = 1883

# ESP32 -> EMQX Cloud -> Local Mosquitto -> Spring Boot
CLOUD_TO_LOCAL_TOPICS = (
    "smarttoll/lanes/2/vehicle-detected",
    "smarttoll/lanes/2/vehicle-crossed",
)

# Spring Boot -> Local Mosquitto -> EMQX Cloud -> ESP32
LOCAL_TO_CLOUD_TOPICS = (
    "smarttoll/lanes/2/gate-command",
)

CA_FILE = "wokwi-esp32/emqxsl-ca.crt"

cloud_ready = threading.Event()
local_ready = threading.Event()


def on_cloud_connect(client, userdata, flags, reason_code, properties):
    print("Cloud connection:", reason_code, flush=True)

    if not reason_code.is_failure:
        cloud_ready.set()

        for topic in CLOUD_TO_LOCAL_TOPICS:
            client.subscribe(topic, qos=1)
            print("Cloud subscribed:", topic, flush=True)


def on_local_connect(client, userdata, flags, reason_code, properties):
    print("Local connection:", reason_code, flush=True)

    if not reason_code.is_failure:
        local_ready.set()

        for topic in LOCAL_TO_CLOUD_TOPICS:
            client.subscribe(topic, qos=1)
            print("Local subscribed:", topic, flush=True)


def on_cloud_message(client, userdata, message):
    if message.topic not in CLOUD_TO_LOCAL_TOPICS:
        return

    if not local_ready.is_set():
        print("Local broker unavailable; message dropped", flush=True)
        return

    result = local_client.publish(
        message.topic,
        payload=message.payload,
        qos=1,
    )

    print(
        f"Cloud -> Local: {message.topic}, result: {result.rc}",
        flush=True,
    )


def on_local_message(client, userdata, message):
    if message.topic not in LOCAL_TO_CLOUD_TOPICS:
        return

    if not cloud_ready.is_set():
        print("Cloud unavailable; message dropped", flush=True)
        return

    result = cloud_client.publish(
        message.topic,
        payload=message.payload,
        qos=1,
    )

    print(
        f"Local -> Cloud: {message.topic}, result: {result.rc}",
        flush=True,
    )


password = os.environ.get("EMQX_BRIDGE_PASSWORD")
if not password:
    password = getpass.getpass("EMQX bridge MQTT password: ")

local_client = mqtt.Client(
    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
    client_id="smarttoll-local-bridge",
)

cloud_client = mqtt.Client(
    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
    client_id="smarttoll-cloud-bridge",
    protocol=mqtt.MQTTv311,
)

cloud_client.username_pw_set("smarttoll-bridge", password)

tls_context = ssl.create_default_context(cafile=CA_FILE)
tls_context.check_hostname = True
cloud_client.tls_set_context(tls_context)

local_client.on_connect = on_local_connect
cloud_client.on_connect = on_cloud_connect
cloud_client.on_message = on_cloud_message
local_client.on_message = on_local_message

try:
    local_client.connect(LOCAL_HOST, LOCAL_PORT, keepalive=30)
    local_client.loop_start()

    if not local_ready.wait(timeout=10):
        raise RuntimeError("Local Mosquitto connection failed")

    cloud_client.connect(CLOUD_HOST, CLOUD_PORT, keepalive=30)
    cloud_client.loop_start()

    if not cloud_ready.wait(timeout=15):
        raise RuntimeError("EMQX Cloud connection failed")

    print("SmartToll Lane 2 MQTT bridge running", flush=True)
    print("Press Ctrl+C to stop", flush=True)

    threading.Event().wait()

except KeyboardInterrupt:
    print("\nStopping bridge...")

finally:
    cloud_client.disconnect()
    local_client.disconnect()
    cloud_client.loop_stop()
    local_client.loop_stop()

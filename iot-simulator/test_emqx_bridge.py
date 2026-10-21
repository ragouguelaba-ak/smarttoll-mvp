import getpass
import ssl
import threading

import paho.mqtt.client as mqtt

HOST = "v57f1141.ala.asia-southeast1.emqxsl.com"
PORT = 8883
USERNAME = "smarttoll-bridge"
CA_FILE = "iot-simulator/wokwi-esp32/emqxsl-ca.crt"

connected = threading.Event()


def on_connect(client, userdata, flags, reason_code, properties):
    print(f"MQTT connection result: {reason_code}", flush=True)
    if not reason_code.is_failure:
        connected.set()


password = getpass.getpass("EMQX bridge MQTT password: ")

client = mqtt.Client(
    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
    client_id="smarttoll-python-bridge-test",
    protocol=mqtt.MQTTv311,
)

client.username_pw_set(USERNAME, password)

tls_context = ssl.create_default_context(cafile=CA_FILE)
tls_context.check_hostname = True
client.tls_set_context(tls_context)

client.on_connect = on_connect

try:
    print("Connecting to EMQX Cloud...")
    client.connect(HOST, PORT, keepalive=30)
    client.loop_start()

    if connected.wait(timeout=15):
        print("SUCCESS: Python connected to EMQX Cloud over TLS!")
    else:
        print("FAILED: MQTT connection was not confirmed.")

finally:
    client.disconnect()
    client.loop_stop()


import json
import time
import threading

import paho.mqtt.client as mqtt

BROKER = "localhost"
PORT = 1883
LANE_IDS = (1,)

processed = set()
lock = threading.Lock()


def command_topic(lane_id):
    return f"smarttoll/lanes/{lane_id}/gate-command"


def crossing_topic(lane_id):
    return f"smarttoll/lanes/{lane_id}/vehicle-crossed"


def simulate_crossing(client, lane_id, event_id, transaction_id):
    time.sleep(3)

    crossing = {
        "eventId": event_id,
        "transactionId": transaction_id,
        "laneId": lane_id,
    }

    result = client.publish(
        crossing_topic(lane_id),
        json.dumps(crossing),
        qos=1,
        retain=False,
    )

    if result.rc == mqtt.MQTT_ERR_SUCCESS:
        print(
            f"Lane {lane_id}: vehicle crossing published "
            f"for transaction {transaction_id}"
        )
    else:
        print(f"Lane {lane_id}: MQTT publish failed: {result.rc}")
        with lock:
            processed.discard((lane_id, event_id, transaction_id))


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        for lane_id in LANE_IDS:
            topic = command_topic(lane_id)
            client.subscribe(topic, qos=1)
            print(f"Subscribed to: {topic}")
    else:
        print(f"MQTT connection failed: {reason_code}")


def on_message(client, userdata, message):
    try:
        parts = message.topic.split("/")

        if (
            len(parts) != 4
            or parts[0] != "smarttoll"
            or parts[1] != "lanes"
            or parts[3] != "gate-command"
        ):
            return

        lane_id = int(parts[2])

        if lane_id not in LANE_IDS:
            return

        data = json.loads(message.payload.decode())

        command = data.get("command")
        event_id = data.get("eventId")
        transaction_id = data.get("transactionId")

        if (
            not isinstance(event_id, str)
            or not event_id
            or not isinstance(transaction_id, int)
            or isinstance(transaction_id, bool)
            or transaction_id <= 0
        ):
            print(f"Lane {lane_id}: ignoring invalid gate command")
            return

        key = (lane_id, event_id, transaction_id)

        if command == "OPEN" and data.get("status") == "AUTHORIZED":
            with lock:
                if key in processed:
                    print(
                        f"Lane {lane_id}: duplicate OPEN ignored "
                        f"for transaction {transaction_id}"
                    )
                    return
                processed.add(key)

            print(f"Lane {lane_id}: gate OPEN, transaction {transaction_id}")
            print(f"Lane {lane_id}: simulating crossing in 3 seconds...")

            threading.Thread(
                target=simulate_crossing,
                args=(client, lane_id, event_id, transaction_id),
                daemon=True,
            ).start()

        elif command == "CLOSED" and data.get("status") == "PASSAGE_COMPLETE":
            print(
                f"Lane {lane_id}: gate CLOSED, "
                f"transaction {transaction_id}"
            )

    except (ValueError, UnicodeDecodeError, TypeError) as error:
        print(f"Invalid MQTT message: {error}")


client = mqtt.Client(
    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
    client_id="smarttoll-dual-lane-simulator",
)

client.on_connect = on_connect
client.on_message = on_message

print("Starting SmartToll dual-lane MQTT gate simulator...")
client.connect(BROKER, PORT, keepalive=60)
client.loop_forever()

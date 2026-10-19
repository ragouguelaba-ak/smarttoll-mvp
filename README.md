# SmartToll MVP

## Prerequisites
Java 17+, Maven 3.9+, Docker Compose, Python 3.10+; optional Python FastAPI.

## Start
```bash
cd infrastructure && docker compose up -d
cd ../backend && mvn spring-boot:run
```

In another terminal:
```bash
curl -X POST http://localhost:8080/api/vehicles -H 'Content-Type: application/json' -d '{"plateNumber":"ABC-123","category":"CAR","initialBalance":2000}'
python3 iot-simulator/simulate.py ABC-123
curl http://localhost:8080/api/vehicles
curl http://localhost:8080/api/transactions
curl http://localhost:8080/api/lanes
```
Run the Python command from the repository root. The demo tariff for a CAR is 500 in unspecified demo currency units.

## AI stub
```bash
cd ai-service
python3 -m pip install fastapi uvicorn
uvicorn app:app --reload --port 8000
```

## Scope and limitations
This is a **development-only simulation**. There is no authentication, physical safety interlock, real OCR, MQTT integration, payment provider, Angular UI, or Flutter UI yet. Do not expose port 8080 publicly or connect this gate command to real hardware. A real barrier requires independent safety sensors, authenticated controller commands, watchdogs and fail-safe operation. Concurrent duplicate event requests can race at the unique constraint; production must catch the unique-key conflict and return the original transaction. The complete-passage endpoint is an explicit simulation trigger, not proof of physical passage. Demo credentials are not suitable for production.

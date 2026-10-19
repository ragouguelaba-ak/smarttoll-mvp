from fastapi import FastAPI
from pydantic import BaseModel
app = FastAPI(title="SmartToll AI Simulator")
class Detection(BaseModel):
    simulated_plate: str
@app.post('/recognize')
def recognize(data: Detection):
    return {"plateNumber":data.simulated_plate.upper(),"confidence":1.0,"source":"SIMULATED"}

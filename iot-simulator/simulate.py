import json, sys, uuid, urllib.request
plate = sys.argv[1] if len(sys.argv)>1 else 'ABC-123'
base = 'http://localhost:8080/api'
def post(path, payload):
    req=urllib.request.Request(base+path, data=json.dumps(payload).encode(), headers={'Content-Type':'application/json'}, method='POST')
    with urllib.request.urlopen(req) as response: return json.load(response)
print('Vehicle detected:',plate)
result=post('/passages',{'eventId':str(uuid.uuid4()),'plateNumber':plate,'laneId':1})
print('Toll decision:',result)
if result.get('gateState')=='OPEN':
    print('Simulated vehicle crossed gate')
    print('Barrier:',post('/lanes/1/complete',{}))

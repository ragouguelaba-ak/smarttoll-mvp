import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface GateStatusEvent {
  laneId: number;
  gateState: 'OPEN' | 'CLOSED';
  timestamp: number;
}

@Injectable({
  providedIn: 'root'
})
export class GateEventsService {

  watchGateEvents(): Observable<GateStatusEvent> {
    return new Observable<GateStatusEvent>(observer => {

      const source = new EventSource(
        'http://localhost:8080/api/gate-events'
      );

      source.addEventListener('gate-status', event => {
        try {
          const data = JSON.parse(
            (event as MessageEvent).data
          ) as GateStatusEvent;

          observer.next(data);
        } catch (error) {
          console.error('Invalid gate event:', error);
        }
      });

      // EventSource reconnects automatically after temporary
      // connection failures.
      return () => source.close();
    });
  }
}

import { Injectable, NgZone, inject } from '@angular/core';
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
  private readonly zone = inject(NgZone);

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

          this.zone.run(() => {
            observer.next(data);
          });
        } catch (error) {
          console.error('Invalid gate event:', error);
        }
      });

      source.onerror = () => {
        console.warn('Gate SSE connection interrupted; reconnecting...');
      };

      return () => source.close();
    });
  }
}

import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Vehicle {
  id?: number;
  vehicleId?: number;
  plate_number: string;
  category: string;
  balance?: number;
  initialBalance?: number;
}

export interface TollTransaction {
  id: number;
  event_id: string;
  vehicle_id: number;
  lane_id: number;
  amount: number;
  status: string;
  created_at: string;
}

export interface TollLane {
  id: number;
  station_id: number;
  lane_code: string;
  gate_state: string;
  payment_mode: 'CASH' | 'RFID_CARD' | 'WALLET';
}

export interface TollPayment {
  id: number;
  transaction_id: number;
  payment_method: 'CASH' | 'RFID_CARD' | 'WALLET';
  amount: number;
  status: string;
  provider_reference: string | null;
  cash_received: number | null;
  change_due: number | null;
  created_at: string;
  lane_id: number;
  vehicle_id: number;
}

export interface PassageResponse {
  transactionId: number;
  status: string;
  fee: number;
  gateState: string;
  replayed: boolean;
  change?: number;
  reason?: string;
  paymentMethod?: string;
}

@Injectable({
  providedIn: 'root'
})
export class SmarttollService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = 'http://localhost:8080/api';

  getVehicles(): Observable<Vehicle[]> {
    return this.http.get<Vehicle[]>(
      `${this.apiUrl}/vehicles`
    );
  }

  registerVehicle(vehicle: {
    plateNumber: string;
    category: string;
    initialBalance: number;
  }): Observable<{ vehicleId: number; plateNumber: string }> {
    return this.http.post<{ vehicleId: number; plateNumber: string }>(
      `${this.apiUrl}/vehicles`,
      vehicle
    );
  }

  getTransactions(): Observable<TollTransaction[]> {
    return this.http.get<TollTransaction[]>(
      `${this.apiUrl}/transactions`
    );
  }

  getLanes(): Observable<TollLane[]> {
    return this.http.get<TollLane[]>(
      `${this.apiUrl}/lanes`
    );
  }

  getPayments(): Observable<TollPayment[]> {
  return this.http.get<TollPayment[]>(
    `${this.apiUrl}/payments`
  );
}

createCashPassage(
  plateNumber: string,
  laneId: number,
  eventId: string
): Observable<PassageResponse> {
  return this.http.post<PassageResponse>(
    `${this.apiUrl}/cash/passages`,
    { plateNumber, laneId, eventId }
  );
}

confirmCashPayment(
  transactionId: number,
  cashReceived: number
): Observable<PassageResponse> {
  return this.http.post<PassageResponse>(
    `${this.apiUrl}/cash/passages/${transactionId}/confirm`,
    { cashReceived }
  );
}

linkSimulatedCard(
  vehicleId: number,
  rfidTag: string,
  paymentToken: string,
  cardLast4: string
): Observable<unknown> {
  return this.http.post(
    `${this.apiUrl}/card/vehicles/${vehicleId}/link`,
    { rfidTag, paymentToken, cardLast4 }
  );
}

simulateCardPassage(
  eventId: string,
  rfidTag: string,
  observedPlate: string,
  laneId: number
): Observable<PassageResponse> {
  return this.http.post<PassageResponse>(
    `${this.apiUrl}/card/passages`,
    { eventId, rfidTag, observedPlate, laneId }
  );
}

completePassage(laneId: number): Observable<unknown> {
  return this.http.post(
    `${this.apiUrl}/lanes/${laneId}/complete`,
    {}
  );
}

}

import {
  Component,
  OnInit,
  inject,
  signal,
  computed
} from '@angular/core';

import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin } from 'rxjs';

import {
  SmarttollService,
  TollTransaction,
  TollPayment,
  TollLane,
  Vehicle
} from '../../core/services/smarttoll';

interface TransactionRow extends TollTransaction {
  plate: string;
  laneCode: string;
  paymentMethod: string;
  paymentStatus: string;
  paymentId: number | null;
}

@Component({
  selector: 'app-transactions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './transactions.html',
  styleUrl: './transactions.css'
})
export class Transactions implements OnInit {

  private readonly api = inject(SmarttollService);

  transactions = signal<TollTransaction[]>([]);
  payments = signal<TollPayment[]>([]);
  vehicles = signal<Vehicle[]>([]);
  lanes = signal<TollLane[]>([]);

  loading = signal(false);
  error = signal('');

  methodFilter = signal('ALL');
  statusFilter = signal('ALL');
  searchText = signal('');

  rows = computed<TransactionRow[]>(() => {
    const paymentMap = new Map(
      this.payments().map(p => [p.transaction_id, p])
    );

    const vehicleMap = new Map(
      this.vehicles().map(v => [v.id, v.plate_number])
    );

    const laneMap = new Map(
      this.lanes().map(l => [l.id, l.lane_code])
    );

    return this.transactions().map(tx => {
      const payment = paymentMap.get(tx.id);

      return {
        ...tx,
        plate: vehicleMap.get(tx.vehicle_id) ?? 'Unknown',
        laneCode: laneMap.get(tx.lane_id) ?? `L${tx.lane_id}`,
        paymentMethod: payment?.payment_method ?? 'LEGACY / UNKNOWN',
        paymentStatus: payment?.status ?? 'NOT RECORDED',
        paymentId: payment?.id ?? null
      };
    });
  });

  filteredRows = computed(() =>
    this.rows().filter(row => {
      const methodMatches =
        this.methodFilter() === 'ALL' ||
        row.paymentMethod === this.methodFilter();

      const statusMatches =
        this.statusFilter() === 'ALL' ||
        row.paymentStatus === this.statusFilter();

      const search = this.searchText().trim().toLowerCase();

      const searchMatches =
        !search ||
        row.plate.toLowerCase().includes(search) ||
        String(row.id).includes(search) ||
        row.laneCode.toLowerCase().includes(search);

      return methodMatches && statusMatches && searchMatches;
    })
  );

  totalTransactions = computed(() =>
    this.transactions().length
  );

  authorizedCharges = computed(() =>
    this.transactions()
      .filter(t => t.status === 'AUTHORIZED')
      .reduce((sum, t) => sum + Number(t.amount ?? 0), 0)
  );

  confirmedRevenue = computed(() =>
    this.payments()
      .filter(p => p.status === 'PAID')
      .reduce((sum, p) => sum + Number(p.amount ?? 0), 0)
  );

  cashRevenue = computed(() =>
    this.payments()
      .filter(p =>
        p.status === 'PAID' &&
        p.payment_method === 'CASH'
      )
      .reduce((sum, p) => sum + Number(p.amount ?? 0), 0)
  );

  cardRevenue = computed(() =>
    this.payments()
      .filter(p =>
        p.status === 'PAID' &&
        p.payment_method === 'RFID_CARD'
      )
      .reduce((sum, p) => sum + Number(p.amount ?? 0), 0)
  );

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    if (this.loading()) return;

    this.loading.set(true);
    this.error.set('');

    forkJoin({
      transactions: this.api.getTransactions(),
      payments: this.api.getPayments(),
      vehicles: this.api.getVehicles(),
      lanes: this.api.getLanes()
    }).subscribe({
      next: data => {
        this.transactions.set(data.transactions);
        this.payments.set(data.payments);
        this.vehicles.set(data.vehicles);
        this.lanes.set(data.lanes);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(
          `Unable to load transaction data: HTTP ${err.status}`
        );
        this.loading.set(false);
      }
    });
  }
}

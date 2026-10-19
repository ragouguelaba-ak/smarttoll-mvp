import {
  Component,
  OnInit,
  OnDestroy,
  inject,
  signal,
  ViewChildren,
  QueryList,
  computed,
} from "@angular/core";

import { CommonModule } from "@angular/common";
import { Subscription, interval, startWith, switchMap } from "rxjs";

import {
  SmarttollService,
  TollLane,
  TollPayment,
} from "../../core/services/smarttoll";

import {
  GateEventsService,
  GateStatusEvent,
} from "../../core/services/gate-events";

import { GateAnimation } from "../../components/gate-animation/gate-animation";

// Interfaces must be declared outside the class.
interface GateHistoryEntry {
  laneId: number;
  state: string;
  timestamp: number;
}

@Component({
  selector: "app-lanes",
  standalone: true,
  imports: [CommonModule, GateAnimation],
  templateUrl: "./lanes.html",
  styleUrl: "./lanes.css",
})
export class Lanes implements OnInit, OnDestroy {
  private readonly api = inject(SmarttollService);
  private readonly events = inject(GateEventsService);

  private subscription?: Subscription;
  private newEventId(): string {
    return crypto.randomUUID();
  }

  busy = signal(false);
  message = signal("");

  cashPlate = signal("OSAKA-3345");
  cashReceived = signal(1000);

  pendingCashTransactionId = signal<number | null>(null);
  pendingCashFee = signal(0);

  rfidTag = signal("RFID-XYZ-456");
  observedPlate = signal("XYZ-456");

  @ViewChildren(GateAnimation)
  gateAnimations!: QueryList<GateAnimation>;

  lanes = signal<TollLane[]>([]);
  error = signal("");
  lastEvent = signal("");
  gateHistory = signal<GateHistoryEntry[]>([]);

  payments = signal<TollPayment[]>([]);

  totalRevenue = computed(() =>
    this.payments()
      .filter((p) => p.status === "PAID")
      .reduce((sum, p) => sum + p.amount, 0),
  );

  paidTransactions = computed(
    () => this.payments().filter((p) => p.status === "PAID").length,
  );

  cashRevenue = computed(() =>
    this.payments()
      .filter((p) => p.status === "PAID" && p.payment_method === "CASH")
      .reduce((sum, p) => sum + p.amount, 0),
  );

  cardRevenue = computed(() =>
    this.payments()
      .filter((p) => p.status === "PAID" && p.payment_method === "RFID_CARD")
      .reduce((sum, p) => sum + p.amount, 0),
  );

  private paymentsSubscription?: Subscription;

  createCashPassage(laneId: number): void {
    if (this.busy() || this.pendingCashTransactionId() !== null) return;

    this.busy.set(true);
    this.error.set("");

    this.api
      .createCashPassage(this.cashPlate().trim(), laneId, this.newEventId())
      .subscribe({
        next: (response) => {
          this.pendingCashTransactionId.set(response.transactionId);
          this.pendingCashFee.set(response.fee);
          this.message.set(
            `Transaction #${response.transactionId}: ¥${response.fee} pending cash`,
          );
          this.busy.set(false);
        },
        error: (err) => {
          this.error.set(`Cash passage failed: ${err.message}`);
          this.busy.set(false);
        },
      });
  }

  confirmCash(): void {
    const transactionId = this.pendingCashTransactionId();

    if (transactionId === null || this.busy()) return;
    if (
      !Number.isFinite(this.cashReceived()) ||
      this.cashReceived() < this.pendingCashFee()
    )
      return;

    this.busy.set(true);
    this.error.set("");

    this.api.confirmCashPayment(transactionId, this.cashReceived()).subscribe({
      next: (response) => {
        this.message.set(
          `Cash paid. Change: ¥${response.change ?? 0}. Barrier opening.`,
        );
        this.pendingCashTransactionId.set(null);
        this.pendingCashFee.set(0);
        this.busy.set(false);
        this.loadLanes();
      },
      error: (err) => {
        this.error.set(`Cash confirmation failed: ${err.message}`);
        this.busy.set(false);
      },
    });
  }

  simulateCard(laneId: number): void {
    if (this.busy()) return;

    this.busy.set(true);
    this.error.set("");

    this.api
      .simulateCardPassage(
        this.newEventId(),
        this.rfidTag().trim(),
        this.observedPlate().trim(),
        laneId,
      )
      .subscribe({
        next: (response) => {
          this.message.set(
            `Card result: ${response.reason ?? response.status}. ` +
              `Transaction #${response.transactionId}`,
          );
          this.busy.set(false);
          this.loadLanes();
        },
        error: (err) => {
          this.error.set(`RFID passage failed: ${err.message}`);
          this.busy.set(false);
        },
      });
  }

  completePassage(laneId: number): void {
    if (this.busy()) return;

    this.busy.set(true);
    this.error.set("");

    this.api.completePassage(laneId).subscribe({
      next: () => {
        this.message.set(`Lane ${laneId}: passage completed`);
        this.busy.set(false);
        this.loadLanes();
      },
      error: (err) => {
        this.error.set(`Cannot close barrier: ${err.message}`);
        this.busy.set(false);
      },
    });
  }

  ngOnInit(): void {
    this.loadLanes();

    this.paymentsSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() => this.api.getPayments()),
      )
      .subscribe({
        next: (payments) => this.payments.set(payments),
        error: (err) => console.error("Payment refresh failed:", err),
      });

    this.subscription = this.events
      .watchGateEvents()
      .subscribe((event: GateStatusEvent) => {
        // Update the live gate state.
        this.lanes.update((currentLanes) =>
          currentLanes.map((lane) =>
            lane.id === event.laneId
              ? {
                  ...lane,
                  gate_state: event.gateState,
                }
              : lane,
          ),
        );

        // Store the latest 20 gate events.
        this.gateHistory.update((history) =>
          [
            {
              laneId: event.laneId,
              state: event.gateState,
              timestamp: event.timestamp,
            },
            ...history,
          ].slice(0, 20),
        );

        const animation = this.gateAnimations?.find(
          (component) =>
            component.laneCode ===
            this.lanes().find((lane) => lane.id === event.laneId)?.lane_code,
        );

        animation?.animateTransition(event.gateState);

        this.lastEvent.set(`Lane ${event.laneId}: ${event.gateState}`);
      });
  }

  loadLanes(): void {
    this.api.getLanes().subscribe({
      next: (data: TollLane[]) => {
        this.lanes.set(data);
        this.error.set("");
      },
      error: (err: Error) => {
        this.error.set(`Unable to load lanes: ${err.message}`);
      },
    });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.paymentsSubscription?.unsubscribe();
  }
}

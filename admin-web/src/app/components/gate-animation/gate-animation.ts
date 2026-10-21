import {
  Component,
  Input,
  OnChanges,
  SimpleChanges,
  OnDestroy,
  signal,
} from "@angular/core";

@Component({
  selector: "app-gate-animation",
  standalone: true,
  templateUrl: "./gate-animation.html",
  styleUrl: "./gate-animation.css",
})
export class GateAnimation implements OnChanges, OnDestroy {
  @Input() laneCode = "L1";
  @Input() gateState = "CLOSED";

  visualState = signal<"OPEN" | "CLOSED">("CLOSED");

  private readonly queue: Array<"OPEN" | "CLOSED"> = [];
  private timer?: ReturnType<typeof setTimeout>;
  private processing = false;
  private destroyed = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes["gateState"]) return;

    const state: "OPEN" | "CLOSED" =
      this.gateState === "OPEN" ? "OPEN" : "CLOSED";

    // Synchronize on initialization.
    if (changes["gateState"].firstChange) {
      this.visualState.set(state);
      return;
    }

    // Preserve queued SSE animations.
    if (this.processing || this.queue.length > 0) {
      return;
    }

    // Synchronize when the input changes outside an animation.
    this.visualState.set(state);
  }

  // Called for each SSE transition, even if OPEN and CLOSED
  // arrive only milliseconds apart.
  animateTransition(state: "OPEN" | "CLOSED"): void {
    this.queue.push(state);
    this.processQueue();
  }

  private processQueue(): void {
    if (this.processing || this.destroyed) return;

    const state = this.queue.shift();

    if (!state) {
      this.processing = false;
      return;
    }

    this.processing = true;
    this.visualState.set(state);

    this.timer = setTimeout(() => {
      this.processing = false;
      this.processQueue();
    }, 1200);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    clearTimeout(this.timer);
    this.queue.length = 0;
  }
}

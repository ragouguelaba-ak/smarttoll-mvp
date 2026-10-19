import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import {
  SmarttollService,
  Vehicle
} from '../../core/services/smarttoll';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css'
})
export class Dashboard implements OnInit {

  private readonly api = inject(SmarttollService);

  vehicles = signal<Vehicle[]>([]);
  loading = signal(false);
  error = signal('');

  ngOnInit(): void {
    this.loadVehicles();
  }

  newVehicle = {
  plateNumber: '',
  category: 'CAR',
  initialBalance: 2000
};

registering = signal(false);
registrationMessage = signal('');

registerVehicle(): void {
  if (!this.newVehicle.plateNumber.trim()) {
    this.registrationMessage.set('License plate is required');
    return;
  }

  this.registering.set(true);
  this.registrationMessage.set('');

  this.api.registerVehicle({
    plateNumber: this.newVehicle.plateNumber.trim().toUpperCase(),
    category: this.newVehicle.category,
    initialBalance: this.newVehicle.initialBalance
  }).subscribe({
    next: () => {
      this.registrationMessage.set('Vehicle registered successfully');
      this.registering.set(false);

      this.newVehicle = {
        plateNumber: '',
        category: 'CAR',
        initialBalance: 2000
      };

      this.loadVehicles();
    },
    error: (err: HttpErrorResponse) => {
      this.registrationMessage.set(
        `Registration failed (HTTP ${err.status || 'network error'})`
      );
      this.registering.set(false);
    }
  });
}

  loadVehicles(): void {
    this.loading.set(true);
    this.error.set('');

    this.api.getVehicles().subscribe({
      next: (vehicles: Vehicle[]) => {
        this.vehicles.set(vehicles);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(
          `Unable to load vehicles (HTTP ${err.status || 'network error'})`
        );
        this.loading.set(false);
        console.error('SmartToll API error:', err);
      }
    });
  }
}
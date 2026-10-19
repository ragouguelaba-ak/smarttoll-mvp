import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'dashboard',
    pathMatch: 'full'
  },
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./pages/dashboard/dashboard')
        .then(m => m.Dashboard)
  },
  {
    path: 'transactions',
    loadComponent: () =>
      import('./pages/transactions/transactions')
        .then(m => m.Transactions)
  },
  {
    path: 'lanes',
    loadComponent: () =>
      import('./pages/lanes/lanes')
        .then(m => m.Lanes)
  }
];
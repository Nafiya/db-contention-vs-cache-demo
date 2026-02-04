import { Routes } from '@angular/router';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { CalendarComponent } from './pages/calendar/calendar.component';
import { LoadTestComponent } from './pages/load-test/load-test.component';
import { ComparisonComponent } from './pages/comparison/comparison.component';
import { CacheStatsComponent } from './pages/cache-stats/cache-stats.component';
import { SyncStatusComponent } from './pages/sync-status/sync-status.component';

export const routes: Routes = [
  { path: '', redirectTo: '/calendar', pathMatch: 'full' },
  // { path: 'dashboard', component: DashboardComponent },
  { path: 'calendar', component: CalendarComponent },
  { path: 'load-test', component: LoadTestComponent },
  { path: 'comparison', component: ComparisonComponent },
  { path: 'sync', component: SyncStatusComponent },
  { path: '**', redirectTo: '/dashboard' }
];

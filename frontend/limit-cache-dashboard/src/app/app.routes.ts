import { Routes } from '@angular/router';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { CalendarComponent } from './pages/calendar/calendar.component';
import { LoadTestComponent } from './pages/load-test/load-test.component';
import { ComparisonComponent } from './pages/comparison/comparison.component';
import { CacheStatsComponent } from './pages/cache-stats/cache-stats.component';
import { SyncStatusComponent } from './pages/sync-status/sync-status.component';
import { LiveDemoComponent } from './pages/live-demo/live-demo.component';
import { HowItWorksComponent } from './pages/how-it-works/how-it-works.component';

export const routes: Routes = [
  { path: '', redirectTo: '/live-demo', pathMatch: 'full' },
  { path: 'live-demo', component: LiveDemoComponent },
  { path: 'how-it-works', component: HowItWorksComponent },
  // { path: 'dashboard', component: DashboardComponent },
  { path: 'calendar', component: CalendarComponent },
  { path: 'load-test', component: LoadTestComponent },
  { path: 'comparison', component: ComparisonComponent },
  // { path: 'cache', component: CacheStatsComponent },
  { path: 'sync', component: SyncStatusComponent },
  { path: '**', redirectTo: '/live-demo' }
];

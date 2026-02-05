import { Routes } from '@angular/router';
import { LiveDemoComponent } from './pages/live-demo/live-demo.component';
import { HowItWorksComponent } from './pages/how-it-works/how-it-works.component';

export const routes: Routes = [
  { path: '', redirectTo: '/live-demo', pathMatch: 'full' },
  { path: 'live-demo', component: LiveDemoComponent },
  { path: 'how-it-works', component: HowItWorksComponent },
  { path: '**', redirectTo: '/live-demo' }
];

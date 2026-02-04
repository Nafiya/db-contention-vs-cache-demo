import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './components/header/header.component';
import { SidebarComponent } from './components/sidebar/sidebar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent, SidebarComponent],
  template: `
    <div class="app-container">
      <app-header></app-header>
      <app-sidebar></app-sidebar>
      <main class="main-content">
        <router-outlet></router-outlet>
      </main>
    </div>
  `,
  styles: [`
    .app-container {
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }
    .main-content {
      flex: 1;
      margin-left: 260px;
      margin-top: 90px;
      padding: 2rem;
      max-width: 1400px;
      width: calc(100% - 260px);
    }
    @media (max-width: 1024px) {
      .main-content {
        margin-left: 80px;
        width: calc(100% - 80px);
      }
    }
    @media (max-width: 768px) {
      .main-content {
        margin-left: 0;
        width: 100%;
        padding: 1rem;
      }
    }
  `]
})
export class AppComponent {
  title = 'Limit Cache Dashboard';
}

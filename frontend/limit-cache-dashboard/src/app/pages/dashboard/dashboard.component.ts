import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  todayLimit: any = null;
  cacheStats: any = null;
  status: any = null;
  loading = true;
  error: string | null = null;

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadData();
  }

  loadData() {
    this.loading = true;
    this.error = null;

    this.apiService.getStatus().subscribe({
      next: (res) => this.status = res.data,
      error: (err) => console.error('Status error:', err)
    });

    this.apiService.getTodayLimit().subscribe({
      next: (res) => this.todayLimit = res.data,
      error: (err) => console.error('Today limit error:', err)
    });

    this.apiService.getCacheStats().subscribe({
      next: (res) => {
        this.cacheStats = res.data;
        this.loading = false;
      },
      error: (err) => {
        console.error('Cache stats error:', err);
        this.loading = false;
        this.error = 'Failed to load dashboard data. Is the backend running?';
      }
    });
  }

  consumeLimit() {
    const request = { amount: 100, description: 'Manual test' };
    this.apiService.consumeLimit(request).subscribe({
      next: () => this.loadData(),
      error: (err) => this.error = 'Failed to consume limit: ' + (err.error?.message || err.message)
    });
  }

  warmCache() {
    const now = new Date();
    this.apiService.warmCache(now.getFullYear(), now.getMonth() + 1).subscribe({
      next: () => this.loadData(),
      error: (err) => this.error = 'Failed to warm cache: ' + (err.error?.message || err.message)
    });
  }

  clearCache() {
    this.apiService.clearCache().subscribe({
      next: () => this.loadData(),
      error: (err) => this.error = 'Failed to clear cache: ' + (err.error?.message || err.message)
    });
  }
}

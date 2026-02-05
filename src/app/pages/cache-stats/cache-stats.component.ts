import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-cache-stats',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './cache-stats.component.html',
  styleUrls: ['./cache-stats.component.css']
})
export class CacheStatsComponent implements OnInit {
  stats: any = null;
  loading = true;
  error: string | null = null;

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadStats();
  }

  loadStats() {
    this.loading = true;
    this.error = null;

    this.apiService.getCacheStats().subscribe({
      next: (res) => {
        this.stats = res.data;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load cache stats: ' + (err.error?.message || err.message);
        this.loading = false;
      }
    });
  }

  warmCache() {
    const now = new Date();
    this.apiService.warmCache(now.getFullYear(), now.getMonth() + 1).subscribe({
      next: () => this.loadStats(),
      error: (err) => this.error = 'Failed to warm cache: ' + (err.error?.message || err.message)
    });
  }

  clearCache() {
    if (!confirm('Are you sure you want to clear the cache?')) return;

    this.apiService.clearCache().subscribe({
      next: () => this.loadStats(),
      error: (err) => this.error = 'Failed to clear cache: ' + (err.error?.message || err.message)
    });
  }
}

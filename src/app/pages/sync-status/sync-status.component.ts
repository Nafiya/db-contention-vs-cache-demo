import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-sync-status',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './sync-status.component.html',
  styleUrls: ['./sync-status.component.css']
})
export class SyncStatusComponent implements OnInit {
  stats: any = null;
  loading = true;
  error: string | null = null;
  syncing = false;

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadStats();
  }

  loadStats() {
    this.loading = true;
    this.error = null;

    this.apiService.getSyncStats().subscribe({
      next: (res) => {
        this.stats = res.data;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load sync stats: ' + (err.error?.message || err.message);
        this.loading = false;
      }
    });
  }

  triggerSync() {
    this.syncing = true;
    this.error = null;

    this.apiService.triggerSync().subscribe({
      next: () => {
        this.syncing = false;
        this.loadStats();
      },
      error: (err) => {
        this.error = 'Sync failed: ' + (err.error?.message || err.message);
        this.syncing = false;
      }
    });
  }
}

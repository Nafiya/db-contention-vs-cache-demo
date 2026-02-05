import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css']
})
export class HeaderComponent implements OnInit, OnDestroy {
  status: any = null;
  private statusInterval: ReturnType<typeof setInterval> | null = null;

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadStatus();
    this.statusInterval = setInterval(() => this.loadStatus(), 10000);
  }

  ngOnDestroy() {
    if (this.statusInterval) {
      clearInterval(this.statusInterval);
    }
  }

  loadStatus() {
    this.apiService.getStatus().subscribe({
      next: (response) => this.status = response.data,
      error: (err) => console.error('Status error:', err)
    });
  }
}

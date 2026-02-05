import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-calendar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './calendar.component.html',
  styleUrls: ['./calendar.component.css']
})
export class CalendarComponent implements OnInit {
  year: number;
  month: number;
  monthlyLimits: any[] = [];
  loading = true;
  error: string | null = null;

  constructor(private apiService: ApiService) {
    const now = new Date();
    this.year = now.getFullYear();
    this.month = now.getMonth() + 1;
  }

  ngOnInit() {
    this.loadLimits();
  }

  loadLimits() {
    this.loading = true;
    this.error = null;

    this.apiService.getMonthlyLimits(this.year, this.month).subscribe({
      next: (res) => {
        this.monthlyLimits = res.data?.limits || [];
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load monthly limits: ' + (err.error?.message || err.message);
        this.loading = false;
      }
    });
  }

  trackByLimitDate(index: number, limit: any) {
    return limit.date || limit.limitDate;
  }

  prevMonth() {
    this.month--;
    if (this.month < 1) {
      this.month = 12;
      this.year--;
    }
    this.loadLimits();
  }

  nextMonth() {
    this.month++;
    if (this.month > 12) {
      this.month = 1;
      this.year++;
    }
    this.loadLimits();
  }

  getMonthName(): string {
    return new Date(this.year, this.month - 1).toLocaleString('default', { month: 'long' });
  }

  resetLimits() {
    if (!confirm('Are you sure you want to reset limits for this month?')) return;

    this.apiService.resetLimits(this.year, this.month).subscribe({
      next: () => this.loadLimits(),
      error: (err) => this.error = 'Failed to reset: ' + (err.error?.message || err.message)
    });
  }
}

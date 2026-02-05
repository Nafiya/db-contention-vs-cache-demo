import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-comparison',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './comparison.component.html',
  styleUrls: ['./comparison.component.css']
})
export class ComparisonComponent implements OnInit {
  threads = 50;
  totalRequests = 50000;
  running = false;
  result: any = null;
  error: string | null = null;
  history: any[] = [];

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadHistory();
  }

  loadHistory() {
    this.apiService.getComparisonHistory().subscribe({
      next: (res) => this.history = res.data || [],
      error: () => {}
    });
  }

  runComparison() {
    this.running = true;
    this.error = null;
    this.result = null;

    this.apiService.runComparisonTest(this.threads, this.totalRequests).subscribe({
      next: (res) => {
        this.result = res.data;
        this.running = false;
        this.loadHistory();
      },
      error: (err) => {
        this.error = 'Comparison test failed: ' + (err.error?.message || err.message);
        this.running = false;
      }
    });
  }
}

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-load-test',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './load-test.component.html',
  styleUrls: ['./load-test.component.css']
})
export class LoadTestComponent implements OnInit {
  threads = 50;
  totalRequests = 50000;
  useCache = true;
  running = false;
  result: any = null;
  error: string | null = null;
  history: any[] = [];

  constructor(private apiService: ApiService) {}

  ngOnInit() {
    this.loadHistory();
  }

  loadHistory() {
    this.apiService.getLoadTestHistory().subscribe({
      next: (res) => this.history = res.data || [],
      error: () => {}
    });
  }

  runLoadTest() {
    this.running = true;
    this.error = null;
    this.result = null;

    const config = {
      threads: this.threads,
      totalRequests: this.totalRequests,
      useCache: this.useCache
    };

    this.apiService.runLoadTest(config).subscribe({
      next: (res) => {
        this.result = res.data;
        this.running = false;
        this.loadHistory();
      },
      error: (err) => {
        this.error = 'Load test failed: ' + (err.error?.message || err.message);
        this.running = false;
      }
    });
  }

  runQuickTest(useCache: boolean) {
    this.running = true;
    this.error = null;
    this.result = null;

    this.apiService.quickTest(useCache).subscribe({
      next: (res) => {
        this.result = res.data;
        this.running = false;
        this.loadHistory();
      },
      error: (err) => {
        this.error = 'Quick test failed: ' + (err.error?.message || err.message);
        this.running = false;
      }
    });
  }
}

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private baseUrl = environment.apiUrl;

  constructor(private http: HttpClient) {}

  getStatus(): Observable<any> {
    return this.http.get(`${this.baseUrl}/limits/status`);
  }

  getTodayLimit(): Observable<any> {
    return this.http.get(`${this.baseUrl}/limits/today`);
  }

  getMonthlyLimits(year: number, month: number): Observable<any> {
    return this.http.get(`${this.baseUrl}/limits/${year}/${month}`);
  }

  consumeLimit(request: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/limits/consume`, request);
  }

  warmCache(year: number, month: number): Observable<any> {
    return this.http.post(`${this.baseUrl}/limits/cache/warm?year=${year}&month=${month}`, {});
  }

  clearCache(): Observable<any> {
    return this.http.post(`${this.baseUrl}/limits/cache/clear`, {});
  }

  getCacheStats(): Observable<any> {
    return this.http.get(`${this.baseUrl}/limits/cache/stats`);
  }

  triggerSync(): Observable<any> {
    return this.http.post(`${this.baseUrl}/limits/sync`, {});
  }

  getSyncStats(): Observable<any> {
    return this.http.get(`${this.baseUrl}/limits/sync/stats`);
  }

  resetLimits(year: number, month: number): Observable<any> {
    return this.http.post(`${this.baseUrl}/limits/reset?year=${year}&month=${month}`, {});
  }

  runLoadTest(config: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/demo/load-test`, config);
  }

  quickTest(useCache: boolean): Observable<any> {
    return this.http.get(`${this.baseUrl}/demo/quick-test?useCache=${useCache}`);
  }

  runComparisonTest(threads: number, totalRequests: number): Observable<any> {
    return this.http.post(
      `${this.baseUrl}/demo/comparison-test?threads=${threads}&totalRequests=${totalRequests}`,
      {}
    );
  }

  getLoadTestHistory(): Observable<any> {
    return this.http.get(`${this.baseUrl}/demo/history/load-tests`);
  }

  getComparisonHistory(): Observable<any> {
    return this.http.get(`${this.baseUrl}/demo/history/comparisons`);
  }
}

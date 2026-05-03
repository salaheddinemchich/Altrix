import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { UserService } from '../services/user.service';

/**
 * Attaches {@code X-User-Id} to every outbound request.
 *
 * <p>Backend controllers reject calls missing this header (see
 * {@code @RequestHeader("X-User-Id")} on ProjectController / JobController).
 */
export const userIdInterceptor: HttpInterceptorFn = (req, next) => {
  const userId = inject(UserService).currentUserId();
  return next(req.clone({ setHeaders: { 'X-User-Id': userId } }));
};

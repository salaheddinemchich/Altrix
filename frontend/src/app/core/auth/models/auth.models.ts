export type AuthProvider = 'GITHUB' | 'GITLAB';

export interface UserProfile {
  readonly userId: string;
  readonly login: string;
  readonly email: string | null;
  readonly role: 'ROLE_USER' | 'ROLE_ADMIN' | string;
  readonly provider: AuthProvider | string;
}

export interface TokenResponse {
  readonly accessToken: string;
  readonly expiresInMinutes: number;
  readonly role: string;
}

export interface AuthState {
  readonly user: UserProfile | null;
  readonly accessToken: string | null;
  readonly loading: boolean;
  readonly error: string | null;
}

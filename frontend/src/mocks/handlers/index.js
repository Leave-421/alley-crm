import { commonHandlers } from './common.js';
import { accountHandlers } from './account.js';
import { reservationHandlers } from './reservation.js';
import { recommendationHandlers } from './recommendation.js';

// 목 핸들러는 CONTRACTS_API.md 절(담당)별 파일로 나눈다. 이 파일은 합치기만 한다.
export const handlers = [...commonHandlers, ...accountHandlers, ...reservationHandlers, ...recommendationHandlers];

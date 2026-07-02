import { notifications } from '@mantine/notifications';

export function notifyError(title: string, error: unknown): void {
  notifications.show({
    color: 'red',
    title,
    message: error instanceof Error ? error.message : String(error),
    autoClose: 5000,
  });
}

export function notifySuccess(message: string): void {
  notifications.show({
    color: 'green',
    message,
    autoClose: 3000,
  });
}

export function notifyInfo(message: string): void {
  notifications.show({
    color: 'blue',
    message,
    autoClose: 3000,
  });
}

import { describe, it, expect, vi } from 'vitest';
import { notifications } from '@mantine/notifications';
import { notifyError, notifySuccess, notifyInfo } from './notifications';

vi.mock('@mantine/notifications', () => ({
  notifications: {
    show: vi.fn(),
  },
}));

const mockedShow = vi.mocked(notifications.show);

describe('notifications helpers', () => {
  it('notifyError calls notifications.show with red color and error message', () => {
    notifyError('Operation failed', new Error('boom'));
    expect(mockedShow).toHaveBeenCalledWith({
      color: 'red',
      title: 'Operation failed',
      message: 'boom',
      autoClose: 5000,
    });
  });

  it('notifyError stringifies non-Error objects', () => {
    notifyError('Weird error', 'plain string');
    expect(mockedShow).toHaveBeenCalledWith(expect.objectContaining({ message: 'plain string' }));
  });

  it('notifyError handles number and boolean', () => {
    notifyError('Num', 42);
    expect(mockedShow).toHaveBeenCalledWith(expect.objectContaining({ message: '42' }));
  });

  it('notifySuccess calls notifications.show with green color', () => {
    notifySuccess('Saved');
    expect(mockedShow).toHaveBeenCalledWith({
      color: 'green',
      message: 'Saved',
      autoClose: 3000,
    });
  });

  it('notifyInfo calls notifications.show with blue color', () => {
    notifyInfo('FYI');
    expect(mockedShow).toHaveBeenCalledWith({
      color: 'blue',
      message: 'FYI',
      autoClose: 3000,
    });
  });
});

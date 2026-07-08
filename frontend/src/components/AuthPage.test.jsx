import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuthPage from './AuthPage';
import * as api from '../api';

jest.mock('../api');

// The tab and the submit button share the same accessible name ("Login" /
// "Register"), so the submit is targeted by its class.
const submitButton = () => document.querySelector('.auth-submit');

describe('AuthPage', () => {
  beforeEach(() => jest.clearAllMocks());

  test('successful login passes auth data to onLogin', async () => {
    const authData = { token: 'jwt-123', name: 'Abs' };
    api.login.mockResolvedValue(authData);
    const onLogin = jest.fn();
    render(<AuthPage onLogin={onLogin} />);

    userEvent.type(screen.getByLabelText('Email'), 'a@b.com');
    userEvent.type(screen.getByLabelText('Password'), 'secret');
    fireEvent.click(submitButton());

    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(authData));
    expect(api.login).toHaveBeenCalledWith('a@b.com', 'secret');
  });

  test('failed login shows the error and does not log in', async () => {
    api.login.mockRejectedValue(new Error('Invalid email or password'));
    const onLogin = jest.fn();
    render(<AuthPage onLogin={onLogin} />);

    userEvent.type(screen.getByLabelText('Email'), 'a@b.com');
    userEvent.type(screen.getByLabelText('Password'), 'wrong');
    fireEvent.click(submitButton());

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
    expect(onLogin).not.toHaveBeenCalled();
  });

  test('register tab shows the name field and submits registration', async () => {
    const authData = { token: 'jwt-456' };
    api.register.mockResolvedValue(authData);
    const onLogin = jest.fn();
    render(<AuthPage onLogin={onLogin} />);

    fireEvent.click(screen.getByRole('button', { name: 'Register' }));

    userEvent.type(screen.getByLabelText('Name'), 'Abs');
    userEvent.type(screen.getByLabelText('Email'), 'a@b.com');
    userEvent.type(screen.getByLabelText('Password'), 'secret');
    fireEvent.click(submitButton());

    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(authData));
    expect(api.register).toHaveBeenCalledWith('a@b.com', 'secret', 'Abs');
  });

  test('switching tabs clears a previous error', async () => {
    api.login.mockRejectedValue(new Error('Invalid email or password'));
    render(<AuthPage onLogin={jest.fn()} />);

    userEvent.type(screen.getByLabelText('Email'), 'a@b.com');
    userEvent.type(screen.getByLabelText('Password'), 'wrong');
    fireEvent.click(submitButton());
    await screen.findByText('Invalid email or password');

    fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    expect(screen.queryByText('Invalid email or password')).not.toBeInTheDocument();
  });
});

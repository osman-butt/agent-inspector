async function request(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new Error(`${options?.method ?? 'GET'} ${url} failed: ${response.status}`);
    }
    return response.status === 204 ? null : response.json();
}

export const listExchanges = () => request('api/exchanges');

export const getExchange = id => request(`api/exchanges/${encodeURIComponent(id)}`);

export const clearExchanges = () => request('api/exchanges', {method: 'DELETE'});

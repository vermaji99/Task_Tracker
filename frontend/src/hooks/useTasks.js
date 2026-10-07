import { useState, useEffect, useRef } from 'react';
import { fetchTasks } from '../api';

export function useTasks(query, status, page, pageSize) {
  const [tasks, setTasks] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestIdRef = useRef(0);

  useEffect(() => {
    const currentRequestId = ++requestIdRef.current;
    const controller = new AbortController();

    setLoading(true);
    setError(null);

    fetchTasks({ query, status, page, pageSize, signal: controller.signal })
      .then((data) => {
        if (currentRequestId !== requestIdRef.current) return;
        setTasks(data.items || []);
        setTotal(data.total || 0);
        setError(null);
      })
      .catch((err) => {
        if (err.name === 'AbortError') return;
        if (currentRequestId !== requestIdRef.current) return;
        setError(err.message);
      })
      .finally(() => {
        if (currentRequestId !== requestIdRef.current) return;
        setLoading(false);
      });

    return () => {
      controller.abort();
    };
  }, [query, status, page, pageSize]);

  return { tasks, total, loading, error };
}

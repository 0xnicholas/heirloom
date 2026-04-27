use rand::Rng;
use crate::config::SecretString;

#[derive(Debug, Clone)]
pub struct WeightedKey {
    pub value: SecretString,
    pub weight: f64,
}

pub fn select_key(keys: &[WeightedKey]) -> &WeightedKey {
    if keys.is_empty() {
        panic!("Cannot select key from empty list");
    }
    
    // Integer-based calculation (like Bifrost: weight * 100)
    let total_weight: u64 = keys.iter()
        .map(|k| (k.weight * 100.0) as u64)
        .sum();
    
    if total_weight == 0 {
        // Fall back to uniform random
        let idx = rand::thread_rng().gen_range(0..keys.len());
        return &keys[idx];
    }
    
    let mut rng = rand::thread_rng();
    let mut point = rng.gen_range(0..total_weight);
    
    for key in keys {
        let weight = (key.weight * 100.0) as u64;
        if point < weight {
            return key;
        }
        point -= weight;
    }
    
    &keys[keys.len() - 1]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_weighted_random_selection() {
        let keys = vec![
            WeightedKey { value: SecretString::new("key-a"), weight: 1.0 },
            WeightedKey { value: SecretString::new("key-b"), weight: 2.0 },
        ];
        
        let selected = select_key(&keys);
        assert!(keys.iter().any(|k| k.value.expose() == selected.value.expose()));
    }

    #[test]
    fn test_weighted_distribution() {
        let keys = vec![
            WeightedKey { value: SecretString::new("key-a"), weight: 1.0 },
            WeightedKey { value: SecretString::new("key-b"), weight: 3.0 },
        ];
        
        let mut counts = std::collections::HashMap::new();
        for _ in 0..1000 {
            let selected = select_key(&keys);
            *counts.entry(selected.value.expose().to_string()).or_insert(0) += 1;
        }
        
        let count_a = counts.get("key-a").unwrap_or(&0);
        let count_b = counts.get("key-b").unwrap_or(&0);
        
        // key-b should be selected ~3x more often than key-a
        let ratio = *count_b as f64 / *count_a as f64;
        assert!(ratio > 2.0 && ratio < 4.0, "Ratio was {}", ratio);
    }
}

package cv.sitech.adapter.out.persistence.mapper;

public interface EntityMapper<D, E> {
  D toDomain(E entity);

  E toEntity(D domain);
}
